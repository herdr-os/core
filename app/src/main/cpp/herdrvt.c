// JNI bridge from Kotlin (dev.r1ptt.herdr.vt.GhosttyVt) to libghostty-vt, Ghostty's terminal
// emulation library. Kotlin gets an opaque handle and, per frame, one flat int[] snapshot:
//   [rows, cols, cursorRow, cursorCol, defaultFg, defaultBg,
//    then per cell: codepoint, fg, bg (0 = none), flags]
// Colors are opaque ARGB, so 0 (transparent) can never be a real color; -1 would be white.
// One array per frame keeps JNI crossings out of the per-cell loop.

#include <jni.h>
#include <stdlib.h>
#include <ghostty/vt.h>

#define HEADER 6
#define PER_CELL 4
#define FLAG_BOLD 1
#define FLAG_ITALIC 2
#define FLAG_FAINT 4
#define FLAG_INVERSE 8
#define FLAG_UNDERLINE 16

typedef struct {
    GhosttyTerminal terminal;
    GhosttyRenderState state;
    GhosttyRenderStateRowIterator rows;
    GhosttyRenderStateRowCells cells;
    uint16_t cols, rows_n;
} Vt;

static int rgb(GhosttyColorRgb c) { return (int)(0xFF000000u | ((uint32_t)c.r << 16) | ((uint32_t)c.g << 8) | c.b); }

static void vt_free(Vt *vt) {
    if (vt->cells) ghostty_render_state_row_cells_free(vt->cells);
    if (vt->rows) ghostty_render_state_row_iterator_free(vt->rows);
    if (vt->state) ghostty_render_state_free(vt->state);
    if (vt->terminal) ghostty_terminal_free(vt->terminal);
    free(vt);
}

JNIEXPORT jlong JNICALL Java_dev_r1ptt_herdr_vt_GhosttyVt_nativeNew(JNIEnv *env, jclass cls, jint cols, jint rows) {
    Vt *vt = calloc(1, sizeof(Vt));
    if (!vt) return 0;
    vt->cols = (uint16_t)cols;
    vt->rows_n = (uint16_t)rows;
    if (ghostty_terminal_new(NULL, &vt->terminal, vt->cols, vt->rows_n) != GHOSTTY_SUCCESS ||
        ghostty_render_state_new(NULL, &vt->state) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_iterator_new(NULL, &vt->rows) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_cells_new(NULL, &vt->cells) != GHOSTTY_SUCCESS) {
        vt_free(vt);
        return 0;
    }
    return (jlong)(intptr_t)vt;
}

JNIEXPORT void JNICALL Java_dev_r1ptt_herdr_vt_GhosttyVt_nativeWrite(JNIEnv *env, jclass cls, jlong handle, jbyteArray data, jint len) {
    Vt *vt = (Vt *)(intptr_t)handle;
    jbyte *bytes = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (!bytes) return;
    ghostty_terminal_vt_write(vt->terminal, (const uint8_t *)bytes, (size_t)len);
    (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
}

JNIEXPORT jintArray JNICALL Java_dev_r1ptt_herdr_vt_GhosttyVt_nativeSnapshot(JNIEnv *env, jclass cls, jlong handle) {
    Vt *vt = (Vt *)(intptr_t)handle;
    if (ghostty_render_state_update(vt->state, vt->terminal) != GHOSTTY_SUCCESS) return NULL;
    GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    ghostty_render_state_colors_get(vt->state, &colors);

    size_t total = HEADER + (size_t)vt->rows_n * vt->cols * PER_CELL;
    jint *out = calloc(total, sizeof(jint));
    if (!out) return NULL;
    out[0] = vt->rows_n;
    out[1] = vt->cols;
    bool cursor = false;
    uint16_t cx = 0, cy = 0;
    ghostty_render_state_get(vt->state, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE, &cursor);
    if (cursor) {
        ghostty_render_state_get(vt->state, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X, &cx);
        ghostty_render_state_get(vt->state, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y, &cy);
    }
    out[2] = cursor ? cy : -1;
    out[3] = cursor ? cx : -1;
    out[4] = rgb(colors.foreground);
    out[5] = rgb(colors.background);

    if (ghostty_render_state_get(vt->state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &vt->rows) == GHOSTTY_SUCCESS) {
        size_t row = 0;
        while (row < vt->rows_n && ghostty_render_state_row_iterator_next(vt->rows)) {
            if (ghostty_render_state_row_get(vt->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &vt->cells) != GHOSTTY_SUCCESS) { row++; continue; }
            size_t col = 0;
            while (col < vt->cols && ghostty_render_state_row_cells_next(vt->cells)) {
                jint *c = out + HEADER + (row * vt->cols + col) * PER_CELL;
                uint32_t len = 0;
                ghostty_render_state_row_cells_get(vt->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &len);
                // The buffer must hold every codepoint; hostile output can stack many combining
                // marks on one cell, so oversized graphemes show as U+FFFD instead.
                if (len > 16) {
                    c[0] = 0xFFFD;
                } else if (len > 0) {
                    uint32_t cps[16];
                    ghostty_render_state_row_cells_get(vt->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, cps);
                    c[0] = (jint)cps[0];
                }
                GhosttyColorRgb fg = colors.foreground, bg = colors.background;
                ghostty_render_state_row_cells_get(vt->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &fg);
                c[1] = rgb(fg);
                c[2] = ghostty_render_state_row_cells_get(vt->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &bg) == GHOSTTY_SUCCESS ? rgb(bg) : 0;
                GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
                ghostty_render_state_row_cells_get(vt->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style);
                c[3] = (style.bold ? FLAG_BOLD : 0) | (style.italic ? FLAG_ITALIC : 0) | (style.faint ? FLAG_FAINT : 0) |
                       (style.inverse ? FLAG_INVERSE : 0) | (style.underline ? FLAG_UNDERLINE : 0);
                col++;
            }
            row++;
        }
    }
    jintArray result = (*env)->NewIntArray(env, (jsize)total);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, (jsize)total, out);
    free(out);
    return result;
}

JNIEXPORT void JNICALL Java_dev_r1ptt_herdr_vt_GhosttyVt_nativeFree(JNIEnv *env, jclass cls, jlong handle) {
    if (handle) vt_free((Vt *)(intptr_t)handle);
}
