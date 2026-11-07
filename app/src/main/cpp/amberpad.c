#include <jni.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include <linux/input.h>

#ifndef EVIOCGRAB
#define EVIOCGRAB _IOW('E', 0x90, int)
#endif

/* Exclusive grab of an evdev device: while grabbed, the system gets no pointer events
   (no cursor, no clicks), but the grabbing fd keeps receiving them. */
JNIEXPORT jboolean JNICALL
Java_com_amber_pad_server_Native_grab(JNIEnv *env, jclass cls, jint fd, jboolean on) {
    return ioctl(fd, EVIOCGRAB, (void *)(intptr_t)(on ? 1 : 0)) == 0 ? JNI_TRUE : JNI_FALSE;
}
