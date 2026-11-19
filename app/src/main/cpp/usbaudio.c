// Streams PCM to a USB Audio Class device through isochronous transfers, using the Linux usbfs interface on the file descriptor that Android's
// UsbDeviceConnection opened. Android's Java USB API has no isochronous transfers, so this small engine does that one job: it keeps a few transfers in
// flight, fills each packet from a ring that the app writes, follows the DAC's own pace when it sends feedback, and sends silence when the ring is empty.
// Setting the interface, the rate and the volume is done from Kotlin with control transfers.

#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <android/log.h>

#define TAG "OpenTuneUsb"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define MAX_URBS 16
#define MAX_PACKETS 64

typedef struct {
    int fd;
    int endpoint;
    int max_packet;
    int frame_bytes;
    int packets_per_urb;
    int urbs;
    int packets_per_second;
    // The nominal and the current number of frames per packet, in 16.16 fixed point, and the fraction carried from packet to packet.
    uint32_t nominal;
    _Atomic uint32_t pace;
    uint32_t carry;
    // The feedback endpoint of an asynchronous DAC, or 0.
    int feedback;
    int feedback_bytes;
    int feedback_shift;

    uint8_t *ring;
    size_t ring_size;
    _Atomic size_t read;
    _Atomic size_t write;
    _Atomic int paused;
    _Atomic int running;
    _Atomic int64_t frames_played;
    _Atomic int underruns;

    struct usbdevfs_urb *out[MAX_URBS];
    struct usbdevfs_urb *fb;
    uint8_t *fb_buffer;
    pthread_t thread;
} Engine;

static size_t queued(Engine *e) { return atomic_load(&e->write) - atomic_load(&e->read); }

static struct usbdevfs_urb *new_urb(int endpoint, int packets, int packet_bytes) {
    size_t size = sizeof(struct usbdevfs_urb) + packets * sizeof(struct usbdevfs_iso_packet_desc);
    struct usbdevfs_urb *urb = calloc(1, size);
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = endpoint;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->number_of_packets = packets;
    urb->buffer = calloc(1, packets * packet_bytes);
    urb->buffer_length = packets * packet_bytes;
    return urb;
}

// Fills the packets of one transfer: the frames of each packet come from the pace, the bytes from the ring, and silence where the ring has run dry.
static void fill(Engine *e, struct usbdevfs_urb *urb) {
    uint8_t *out = urb->buffer;
    int total = 0;
    int paused = atomic_load(&e->paused);
    for (int p = 0; p < urb->number_of_packets; p++) {
        uint32_t pace = atomic_load(&e->pace);
        uint32_t sum = e->carry + pace;
        int frames = sum >> 16;
        e->carry = sum & 0xFFFF;
        int bytes = frames * e->frame_bytes;
        if (bytes > e->max_packet) bytes = (e->max_packet / e->frame_bytes) * e->frame_bytes;
        size_t available = paused ? 0 : queued(e);
        size_t take = available < (size_t) bytes ? (available / e->frame_bytes) * e->frame_bytes : (size_t) bytes;
        size_t r = atomic_load(&e->read);
        for (size_t i = 0; i < take; i++) out[total + i] = e->ring[(r + i) % e->ring_size];
        if (take < (size_t) bytes) {
            memset(out + total + take, 0, bytes - take);
            if (!paused) atomic_fetch_add(&e->underruns, 1);
        }
        atomic_store(&e->read, r + take);
        atomic_fetch_add(&e->frames_played, (int64_t) (take / e->frame_bytes));
        urb->iso_frame_desc[p].length = bytes;
        total += bytes;
    }
    urb->buffer_length = total;
}

static void read_feedback(Engine *e, struct usbdevfs_urb *urb) {
    for (int p = 0; p < urb->number_of_packets; p++) {
        if (urb->iso_frame_desc[p].actual_length < (unsigned) e->feedback_bytes) continue;
        uint8_t *b = (uint8_t *) urb->buffer + p * e->feedback_bytes;
        uint32_t value = e->feedback_bytes >= 4 ? (uint32_t) b[0] | ((uint32_t) b[1] << 8) | ((uint32_t) b[2] << 16) | ((uint32_t) b[3] << 24)
                                                : (uint32_t) b[0] | ((uint32_t) b[1] << 8) | ((uint32_t) b[2] << 16);
        // Full speed sends 10.14 frames per frame, high speed 16.16 frames per microframe: both become 16.16 frames per packet.
        uint32_t pace = value << e->feedback_shift;
        // A value far from the nominal pace is a glitch, not a pace.
        if (pace > e->nominal - e->nominal / 8 && pace < e->nominal + e->nominal / 8) atomic_store(&e->pace, pace);
    }
}

static void *loop(void *arg) {
    Engine *e = arg;
    for (int i = 0; i < e->urbs; i++) {
        fill(e, e->out[i]);
        if (ioctl(e->fd, USBDEVFS_SUBMITURB, e->out[i]) < 0) LOG("submit failed: %d", errno);
    }
    if (e->fb) ioctl(e->fd, USBDEVFS_SUBMITURB, e->fb);
    while (atomic_load(&e->running)) {
        struct usbdevfs_urb *done = NULL;
        if (ioctl(e->fd, USBDEVFS_REAPURB, &done) < 0) {
            if (errno == EINTR) continue;
            LOG("reap failed: %d", errno);
            break;
        }
        if (!atomic_load(&e->running)) break;
        if (done == e->fb) {
            read_feedback(e, done);
            ioctl(e->fd, USBDEVFS_SUBMITURB, done);
            continue;
        }
        fill(e, done);
        if (ioctl(e->fd, USBDEVFS_SUBMITURB, done) < 0) { LOG("resubmit failed: %d", errno); break; }
    }
    atomic_store(&e->running, 0);
    return NULL;
}

JNIEXPORT jlong JNICALL
Java_io_github_lnrdo_mytidal_playback_usb_UsbNative_start(JNIEnv *env, jclass cls, jint fd, jint endpoint, jint max_packet, jint frame_bytes, jint rate,
                                                              jint packets_per_second, jint feedback, jint feedback_bytes, jint high_speed, jint ring_bytes) {
    Engine *e = calloc(1, sizeof(Engine));
    e->fd = fd;
    e->endpoint = endpoint;
    e->max_packet = max_packet;
    e->frame_bytes = frame_bytes;
    e->packets_per_second = packets_per_second;
    e->nominal = (uint32_t) (((uint64_t) rate << 16) / packets_per_second);
    atomic_store(&e->pace, e->nominal);
    e->feedback = feedback;
    e->feedback_bytes = feedback_bytes;
    e->feedback_shift = high_speed ? 0 : 2;
    // About 8 ms in each transfer, and four transfers in flight: about 32 ms queued in the DAC's direction.
    e->packets_per_urb = packets_per_second / 125;
    if (e->packets_per_urb < 1) e->packets_per_urb = 1;
    if (e->packets_per_urb > MAX_PACKETS) e->packets_per_urb = MAX_PACKETS;
    e->urbs = 4;
    e->ring_size = ring_bytes;
    e->ring = calloc(1, ring_bytes);
    for (int i = 0; i < e->urbs; i++) e->out[i] = new_urb(endpoint, e->packets_per_urb, max_packet);
    if (feedback) {
        e->fb = new_urb(feedback, 1, feedback_bytes);
        for (int p = 0; p < 1; p++) e->fb->iso_frame_desc[p].length = feedback_bytes;
    }
    atomic_store(&e->running, 1);
    if (pthread_create(&e->thread, NULL, loop, e) != 0) { free(e->ring); free(e); return 0; }
    LOG("started: endpoint %d, %d bytes per frame, %d Hz, %d packets a second, feedback %d", endpoint, frame_bytes, rate, packets_per_second, feedback);
    return (jlong) (intptr_t) e;
}

JNIEXPORT jint JNICALL
Java_io_github_lnrdo_mytidal_playback_usb_UsbNative_write(JNIEnv *env, jclass cls, jlong handle, jobject buffer, jint length) {
    Engine *e = (Engine *) (intptr_t) handle;
    if (!e || !atomic_load(&e->running)) return -1;
    uint8_t *src = (*env)->GetDirectBufferAddress(env, buffer);
    size_t free_bytes = e->ring_size - queued(e);
    size_t n = (size_t) length < free_bytes ? (size_t) length : free_bytes;
    n -= n % e->frame_bytes;
    size_t w = atomic_load(&e->write);
    for (size_t i = 0; i < n; i++) e->ring[(w + i) % e->ring_size] = src[i];
    atomic_store(&e->write, w + n);
    return (jint) n;
}

JNIEXPORT jlong JNICALL
Java_io_github_lnrdo_mytidal_playback_usb_UsbNative_framesPlayed(JNIEnv *env, jclass cls, jlong handle) {
    Engine *e = (Engine *) (intptr_t) handle;
    return e ? atomic_load(&e->frames_played) : 0;
}

JNIEXPORT jint JNICALL
Java_io_github_lnrdo_mytidal_playback_usb_UsbNative_queuedBytes(JNIEnv *env, jclass cls, jlong handle) {
    Engine *e = (Engine *) (intptr_t) handle;
    return e ? (jint) queued(e) : 0;
}

JNIEXPORT jint JNICALL
Java_io_github_lnrdo_mytidal_playback_usb_UsbNative_underruns(JNIEnv *env, jclass cls, jlong handle) {
    Engine *e = (Engine *) (intptr_t) handle;
    return e ? atomic_load(&e->underruns) : 0;
}

JNIEXPORT jboolean JNICALL
Java_io_github_lnrdo_mytidal_playback_usb_UsbNative_running(JNIEnv *env, jclass cls, jlong handle) {
    Engine *e = (Engine *) (intptr_t) handle;
    return e && atomic_load(&e->running);
}

JNIEXPORT void JNICALL
Java_io_github_lnrdo_mytidal_playback_usb_UsbNative_setPaused(JNIEnv *env, jclass cls, jlong handle, jboolean paused) {
    Engine *e = (Engine *) (intptr_t) handle;
    if (e) atomic_store(&e->paused, paused ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_io_github_lnrdo_mytidal_playback_usb_UsbNative_flush(JNIEnv *env, jclass cls, jlong handle) {
    Engine *e = (Engine *) (intptr_t) handle;
    if (!e) return;
    atomic_store(&e->read, atomic_load(&e->write));
}

JNIEXPORT void JNICALL
Java_io_github_lnrdo_mytidal_playback_usb_UsbNative_stop(JNIEnv *env, jclass cls, jlong handle) {
    Engine *e = (Engine *) (intptr_t) handle;
    if (!e) return;
    atomic_store(&e->running, 0);
    // Cancelling the transfers in flight wakes the thread, which then stops.
    for (int i = 0; i < e->urbs; i++) ioctl(e->fd, USBDEVFS_DISCARDURB, e->out[i]);
    if (e->fb) ioctl(e->fd, USBDEVFS_DISCARDURB, e->fb);
    pthread_join(e->thread, NULL);
    // Reap what was cancelled, so that the kernel holds no pointer into memory that is freed next.
    struct usbdevfs_urb *done;
    while (ioctl(e->fd, USBDEVFS_REAPURBNDELAY, &done) == 0) {}
    for (int i = 0; i < e->urbs; i++) { free(e->out[i]->buffer); free(e->out[i]); }
    if (e->fb) { free(e->fb->buffer); free(e->fb); }
    free(e->ring);
    free(e);
}

// 1 low, 2 full, 3 high, 5 super speed, as the kernel names them; -1 when it cannot say.
JNIEXPORT jint JNICALL
Java_io_github_lnrdo_mytidal_playback_usb_UsbNative_speed(JNIEnv *env, jclass cls, jint fd) {
    int speed = ioctl(fd, USBDEVFS_GET_SPEED, 0);
    return speed < 0 ? -1 : speed;
}
