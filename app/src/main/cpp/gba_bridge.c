#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "libretro.h"

#define TAG "GbaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    void *handle;

    char system_dir[1024];
    char save_dir[1024];

    enum retro_pixel_format pixfmt;
    unsigned width;
    unsigned height;
    uint32_t *frame;
    unsigned frame_cap;

    int16_t *audio;
    unsigned audio_cap;   /* samples (not frames), interleaved stereo */
    unsigned audio_head;
    unsigned audio_tail;

    uint32_t buttons;
    int quit;

    struct retro_system_av_info av;

    void (*init)(void);
    void (*deinit)(void);
    unsigned (*api_version)(void);
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*get_system_info)(struct retro_system_info *);
    void (*get_system_av_info)(struct retro_system_av_info *);
    bool (*load_game)(const struct retro_game_info *);
    void (*unload_game)(void);
    void (*run)(void);
    void (*reset)(void);
    void *(*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
} Gba;

static Gba *g_ctx;

static void ensure_frame(Gba *g, unsigned w, unsigned h) {
    unsigned need = w * h;
    if (need > g->frame_cap) {
        uint32_t *p = (uint32_t *) realloc(g->frame, need * sizeof(uint32_t));
        if (p) {
            g->frame = p;
            g->frame_cap = need;
        }
    }
    g->width = w;
    g->height = h;
}

static void cb_video(const void *data, unsigned width, unsigned height, size_t pitch) {
    Gba *g = g_ctx;
    if (!g) return;
    if (width == 0 || height == 0) return;
    ensure_frame(g, width, height);
    if (!data || !g->frame) return;
    const uint8_t *src = (const uint8_t *) data;

    for (unsigned y = 0; y < height; y++) {
        uint32_t *dst = g->frame + (size_t) y * width;
        if (g->pixfmt == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t *row = (const uint32_t *) (src + y * pitch);
            for (unsigned x = 0; x < width; x++) dst[x] = row[x] | 0xFF000000u;
        } else if (g->pixfmt == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t *row = (const uint16_t *) (src + y * pitch);
            for (unsigned x = 0; x < width; x++) {
                uint16_t v = row[x];
                uint32_t r = (uint32_t) ((v >> 11) & 0x1F) << 3;
                uint32_t gg = (uint32_t) ((v >> 5) & 0x3F) << 2;
                uint32_t b = (uint32_t) (v & 0x1F) << 3;
                dst[x] = 0xFF000000u | (r << 16) | (gg << 8) | b;
            }
        } else { /* 0RGB1555 */
            const uint16_t *row = (const uint16_t *) (src + y * pitch);
            for (unsigned x = 0; x < width; x++) {
                uint16_t v = row[x];
                uint32_t r = (uint32_t) ((v >> 10) & 0x1F) << 3;
                uint32_t gg = (uint32_t) ((v >> 5) & 0x1F) << 3;
                uint32_t b = (uint32_t) (v & 0x1F) << 3;
                dst[x] = 0xFF000000u | (r << 16) | (gg << 8) | b;
            }
        }
    }
}

static size_t cb_audio_batch(const int16_t *data, size_t frames) {
    Gba *g = g_ctx;
    if (!g || !data || frames == 0) return frames;
    size_t samples = frames * 2;
    for (size_t i = 0; i < samples; i++) {
        unsigned next = (g->audio_head + 1) % g->audio_cap;
        if (next == g->audio_tail) {
            g->audio_tail = (g->audio_tail + 1) % g->audio_cap; /* drop oldest */
        }
        g->audio[g->audio_head] = data[i];
        g->audio_head = next;
    }
    return frames;
}

static void cb_input_poll(void) {
}

static int16_t cb_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    Gba *g = g_ctx;
    if (!g || port != 0) return 0;
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    if (index != 0) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t) g->buttons;
    if (id > 11) return 0;
    return (g->buttons >> id) & 1;
}

static void cb_log(enum retro_log_level level, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (level == RETRO_LOG_ERROR) LOGE("%s", buf);
    else LOGI("%s", buf);
}

static bool cb_environment(unsigned cmd, void *data) {
    Gba *g = g_ctx;
    if (!g) return false;
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            g->pixfmt = *(const enum retro_pixel_format *) data;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool *) data = true;
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *(const char **) data = g->system_dir;
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **) data = g->save_dir;
            return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            const struct retro_game_geometry *geo = (const struct retro_game_geometry *) data;
            if (geo) {
                g->width = geo->base_width;
                g->height = geo->base_height;
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            struct retro_log_callback *cb = (struct retro_log_callback *) data;
            if (cb) cb->log = cb_log;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK:
        case RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK:
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE:
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            return false;
        case RETRO_ENVIRONMENT_SHUTDOWN:
            g->quit = 1;
            return true;
        default:
            return false;
    }
}

#define LOAD_SYM(field, name) \
    do { \
        *(void **) (&g->field) = dlsym(g->handle, name); \
        if (!g->field) { LOGE("missing symbol %s", name); goto fail; } \
    } while (0)

JNIEXPORT jlong JNICALL
Java_com_rg_player_GbaCore_nativeCreate(JNIEnv *env, jclass clazz, jstring core_path,
                                        jstring rom_path, jstring system_dir, jstring save_dir) {
    const char *core = (*env)->GetStringUTFChars(env, core_path, NULL);
    const char *rom = (*env)->GetStringUTFChars(env, rom_path, NULL);
    const char *sys = (*env)->GetStringUTFChars(env, system_dir, NULL);
    const char *sav = (*env)->GetStringUTFChars(env, save_dir, NULL);

    Gba *g = (Gba *) calloc(1, sizeof(Gba));
    if (!g) goto done;
    g_ctx = g;
    g->pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
    g->audio_cap = 32768 * 2;
    g->audio = (int16_t *) calloc(g->audio_cap, sizeof(int16_t));
    g->buttons = 0;
    snprintf(g->system_dir, sizeof(g->system_dir), "%s", sys);
    snprintf(g->save_dir, sizeof(g->save_dir), "%s", sav);

    g->handle = dlopen(core, RTLD_NOW | RTLD_LOCAL);
    if (!g->handle) {
        LOGE("dlopen failed: %s", dlerror());
        goto fail;
    }

    LOAD_SYM(init, "retro_init");
    LOAD_SYM(deinit, "retro_deinit");
    LOAD_SYM(api_version, "retro_api_version");
    LOAD_SYM(set_environment, "retro_set_environment");
    LOAD_SYM(set_video_refresh, "retro_set_video_refresh");
    LOAD_SYM(set_audio_sample_batch, "retro_set_audio_sample_batch");
    LOAD_SYM(set_input_poll, "retro_set_input_poll");
    LOAD_SYM(set_input_state, "retro_set_input_state");
    LOAD_SYM(get_system_info, "retro_get_system_info");
    LOAD_SYM(get_system_av_info, "retro_get_system_av_info");
    LOAD_SYM(load_game, "retro_load_game");
    LOAD_SYM(unload_game, "retro_unload_game");
    LOAD_SYM(run, "retro_run");
    LOAD_SYM(reset, "retro_reset");
    LOAD_SYM(get_memory_data, "retro_get_memory_data");
    LOAD_SYM(get_memory_size, "retro_get_memory_size");

    g->set_environment(cb_environment);
    g->set_video_refresh(cb_video);
    g->set_audio_sample_batch(cb_audio_batch);
    g->set_input_poll(cb_input_poll);
    g->set_input_state(cb_input_state);

    g->init();

    struct retro_game_info info;
    memset(&info, 0, sizeof(info));
    info.path = rom;
    if (!g->load_game(&info)) {
        LOGE("retro_load_game failed: %s", rom);
        g->deinit();
        goto fail;
    }
    memset(&g->av, 0, sizeof(g->av));
    g->get_system_av_info(&g->av);
    LOGI("loaded %s size=%ux%u rate=%.2f", rom, g->av.geometry.base_width,
         g->av.geometry.base_height, g->av.timing.sample_rate);
    if (g->av.geometry.base_width > 0 && g->av.geometry.base_height > 0) {
        ensure_frame(g, g->av.geometry.base_width, g->av.geometry.base_height);
    }

    (*env)->ReleaseStringUTFChars(env, core_path, core);
    (*env)->ReleaseStringUTFChars(env, rom_path, rom);
    (*env)->ReleaseStringUTFChars(env, system_dir, sys);
    (*env)->ReleaseStringUTFChars(env, save_dir, sav);
    return (jlong) (intptr_t) g;

fail:
    if (g) {
        if (g->handle) dlclose(g->handle);
        free(g->audio);
        free(g->frame);
        free(g);
        g_ctx = NULL;
    }
done:
    (*env)->ReleaseStringUTFChars(env, core_path, core);
    (*env)->ReleaseStringUTFChars(env, rom_path, rom);
    (*env)->ReleaseStringUTFChars(env, system_dir, sys);
    (*env)->ReleaseStringUTFChars(env, save_dir, sav);
    return 0;
}

static Gba *ctx(jlong h) {
    return (Gba *) (intptr_t) h;
}

JNIEXPORT void JNICALL
Java_com_rg_player_GbaCore_nativeRun(JNIEnv *env, jclass clazz, jlong h) {
    Gba *g = ctx(h);
    if (g) g->run();
}

JNIEXPORT jint JNICALL
Java_com_rg_player_GbaCore_nativeWidth(JNIEnv *env, jclass clazz, jlong h) {
    return ctx(h) ? (jint) ctx(h)->width : 0;
}

JNIEXPORT jint JNICALL
Java_com_rg_player_GbaCore_nativeHeight(JNIEnv *env, jclass clazz, jlong h) {
    return ctx(h) ? (jint) ctx(h)->height : 0;
}

JNIEXPORT jint JNICALL
Java_com_rg_player_GbaCore_nativeVideo(JNIEnv *env, jclass clazz, jlong h, jintArray out) {
    Gba *g = ctx(h);
    if (!g || !g->frame || !out) return 0;
    jsize len = (*env)->GetArrayLength(env, out);
    jsize n = (jsize) (g->width * g->height);
    if (n > len) n = len;
    (*env)->SetIntArrayRegion(env, out, 0, n, (const jint *) g->frame);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_rg_player_GbaCore_nativeAudio(JNIEnv *env, jclass clazz, jlong h, jshortArray out) {
    Gba *g = ctx(h);
    if (!g || !g->audio || !out) return 0;
    jsize len = (*env)->GetArrayLength(env, out);
    unsigned avail = (g->audio_head + g->audio_cap - g->audio_tail) % g->audio_cap;
    unsigned n = avail;
    if (n > (unsigned) len) n = (unsigned) len;
    n &= ~1u;
    unsigned first = n;
    unsigned tail = g->audio_tail;
    if (tail + first > g->audio_cap) first = g->audio_cap - tail;
    (*env)->SetShortArrayRegion(env, out, 0, (jsize) first, (const jshort *) (g->audio + tail));
    if (n > first) {
        (*env)->SetShortArrayRegion(env, out, (jsize) first, (jsize) (n - first),
                                    (const jshort *) g->audio);
    }
    g->audio_tail = (tail + n) % g->audio_cap;
    return (jint) n;
}

JNIEXPORT void JNICALL
Java_com_rg_player_GbaCore_nativeButtons(JNIEnv *env, jclass clazz, jlong h, jint mask) {
    Gba *g = ctx(h);
    if (g) g->buttons = (uint32_t) mask;
}

JNIEXPORT jfloat JNICALL
Java_com_rg_player_GbaCore_nativeSampleRate(JNIEnv *env, jclass clazz, jlong h) {
    Gba *g = ctx(h);
    return g ? g->av.timing.sample_rate : 32768.0f;
}

JNIEXPORT jboolean JNICALL
Java_com_rg_player_GbaCore_nativeSaveSram(JNIEnv *env, jclass clazz, jlong h, jstring path) {
    Gba *g = ctx(h);
    if (!g) return JNI_FALSE;
    void *data = g->get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = g->get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return JNI_FALSE;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(p, "wb");
    jboolean ok = JNI_FALSE;
    if (f) {
        ok = fwrite(data, 1, size, f) == size ? JNI_TRUE : JNI_FALSE;
        fclose(f);
    }
    (*env)->ReleaseStringUTFChars(env, path, p);
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_com_rg_player_GbaCore_nativeLoadSram(JNIEnv *env, jclass clazz, jlong h, jstring path) {
    Gba *g = ctx(h);
    if (!g) return JNI_FALSE;
    void *data = g->get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = g->get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return JNI_FALSE;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(p, "rb");
    jboolean ok = JNI_FALSE;
    if (f) {
        size_t n = fread(data, 1, size, f);
        ok = n > 0 ? JNI_TRUE : JNI_FALSE;
        fclose(f);
    }
    (*env)->ReleaseStringUTFChars(env, path, p);
    return ok;
}

JNIEXPORT void JNICALL
Java_com_rg_player_GbaCore_nativeDestroy(JNIEnv *env, jclass clazz, jlong h) {
    Gba *g = ctx(h);
    if (!g) return;
    if (g->handle) {
        if (g->unload_game) g->unload_game();
        if (g->deinit) g->deinit();
        dlclose(g->handle);
    }
    free(g->audio);
    free(g->frame);
    if (g_ctx == g) g_ctx = NULL;
    free(g);
}
