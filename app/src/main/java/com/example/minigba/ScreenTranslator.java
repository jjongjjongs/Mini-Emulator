package com.example.minigba;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.sdkinternal.MlKitContext;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Reads the text on a game frame and translates it to Korean, all on the phone (ML Kit). The
// translation model is downloaded once per language (about 30 MB) and kept by ML Kit.
final class ScreenTranslator {
    static final class Item {
        final Rect box;
        final String text;

        Item(Rect box, String text) {
            this.box = box;
            this.text = text;
        }
    }

    interface Callback {
        // items is null when it failed; error says why.
        void done(List<Item> items, String error);
    }

    private final boolean japanese;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, String> cache = new HashMap<>();
    private TextRecognizer recognizer;
    private Translator translator;
    private volatile boolean modelReady;
    private volatile boolean closed;

    ScreenTranslator(Context context, boolean japanese) {
        this.japanese = japanese;
        try {
            // ML Kit sets itself up only in the main process; games run in their own.
            MlKitContext.initializeIfNeeded(context.getApplicationContext());
        } catch (Throwable ignored) {
            // Already set up.
        }
    }

    boolean modelReady() {
        return this.modelReady;
    }

    // Reads and translates the text in bitmap; boxes are in bitmap pixels, offset by top.
    // onDownload runs (on the main thread) when the model has to be downloaded first.
    void translate(final Bitmap bitmap, final int top, final Runnable onDownload, final Callback callback) {
        worker.execute(() -> {
            List<Item> items = null;
            String error = null;
            try {
                if (this.recognizer == null) {
                    this.recognizer = TextRecognition.getClient(this.japanese
                            ? new JapaneseTextRecognizerOptions.Builder().build()
                            : TextRecognizerOptions.DEFAULT_OPTIONS);
                    this.translator = Translation.getClient(new TranslatorOptions.Builder()
                            .setSourceLanguage(this.japanese ? TranslateLanguage.JAPANESE : TranslateLanguage.ENGLISH)
                            .setTargetLanguage(TranslateLanguage.KOREAN)
                            .build());
                }
                if (!this.modelReady) {
                    if (onDownload != null) {
                        main.post(onDownload);
                    }
                    try {
                        Tasks.await(this.translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()));
                    } catch (Exception e) {
                        throw new IllegalStateException("번역 데이터를 받지 못했어요. 인터넷 연결을 확인하고 다시 눌러 주세요.");
                    }
                    this.modelReady = true;
                }
                Text text = Tasks.await(this.recognizer.process(InputImage.fromBitmap(bitmap, 0)));
                items = new ArrayList<>();
                for (Text.TextBlock block : text.getTextBlocks()) {
                    Rect box = block.getBoundingBox();
                    String source = clean(block);
                    if (box == null || source == null) {
                        continue;
                    }
                    String korean = this.cache.get(source);
                    if (korean == null) {
                        korean = Tasks.await(this.translator.translate(source));
                        this.cache.put(source, korean);
                    }
                    box.offset(0, top);
                    items.add(new Item(box, korean));
                }
            } catch (IllegalStateException e) {
                error = e.getMessage();
            } catch (Throwable e) {
                error = "화면의 글자를 읽지 못했어요.";
            }
            final List<Item> result = items;
            final String why = error;
            main.post(() -> {
                if (!this.closed) {
                    callback.done(result, why);
                }
            });
        });
    }

    // One block's text on one line, or null when there is nothing worth translating.
    private String clean(Text.TextBlock block) {
        StringBuilder out = new StringBuilder();
        for (Text.Line line : block.getLines()) {
            if (out.length() > 0 && !this.japanese) {
                out.append(' ');
            }
            out.append(line.getText().trim());
        }
        String value = out.toString().trim();
        int letters = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) {
                letters++;
            }
        }
        if (letters < (this.japanese ? 1 : 2)) {
            return null;
        }
        // Games like ALL CAPS headings; the translator does better on normal case.
        if (!this.japanese && value.equals(value.toUpperCase(java.util.Locale.ROOT)) && letters > 3) {
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            value = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
        return value;
    }

    void close() {
        this.closed = true;
        worker.execute(() -> {
            if (this.recognizer != null) {
                this.recognizer.close();
                this.translator.close();
            }
        });
        worker.shutdown();
    }
}
