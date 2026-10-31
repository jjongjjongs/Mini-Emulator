package com.example.minigba;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's own confirmation and notice card, in place of the system AlertDialog: a white
 * rounded card with a coloured round icon, a title, the message, an optional view, item
 * rows and up to three buttons. The Builder takes the same calls as AlertDialog.Builder
 * (the subset the app uses), so call sites change only the class name.
 *
 * The kind (which sets the icon and the button colour) is read from the words: a positive
 * button that deletes makes it a red "지우기" card, a title that asks makes it an orange
 * confirmation, a failure in the title makes it a red error, an input view or items make
 * it purple, anything else is a teal notice. Builder.kind overrides that.
 */
final class AppDialog {
    static final int KIND_INFO = 0;
    static final int KIND_CONFIRM = 1;
    static final int KIND_DANGER = 2;
    static final int KIND_ERROR = 3;
    static final int KIND_EDIT = 4;

    private AppDialog() {
    }

    static final class Builder {
        private final Context context;
        private CharSequence title;
        private CharSequence message;
        private View view;
        private CharSequence[] items;
        private DialogInterface.OnClickListener itemsListener;
        private int checkedItem = -1;
        private boolean singleChoice;
        private boolean[] multiChecked;
        private DialogInterface.OnMultiChoiceClickListener multiListener;
        private CharSequence positiveText;
        private DialogInterface.OnClickListener positiveListener;
        private CharSequence negativeText;
        private DialogInterface.OnClickListener negativeListener;
        private CharSequence neutralText;
        private DialogInterface.OnClickListener neutralListener;
        private boolean cancelable = true;
        private DialogInterface.OnDismissListener dismissListener;
        private int kind = -1;

        Builder(Context context) {
            this.context = context;
        }

        Builder setTitle(CharSequence text) {
            this.title = text;
            return this;
        }

        Builder setTitle(int resId) {
            this.title = context.getString(resId);
            return this;
        }

        Builder setMessage(CharSequence text) {
            this.message = text;
            return this;
        }

        Builder setMessage(int resId) {
            this.message = context.getString(resId);
            return this;
        }

        Builder setView(View custom) {
            this.view = custom;
            return this;
        }

        Builder setItems(CharSequence[] names, DialogInterface.OnClickListener listener) {
            this.items = names;
            this.itemsListener = listener;
            this.singleChoice = false;
            return this;
        }

        Builder setSingleChoiceItems(CharSequence[] names, int checked, DialogInterface.OnClickListener listener) {
            this.items = names;
            this.itemsListener = listener;
            this.checkedItem = checked;
            this.singleChoice = true;
            return this;
        }

        Builder setMultiChoiceItems(CharSequence[] names, boolean[] checked, DialogInterface.OnMultiChoiceClickListener listener) {
            this.items = names;
            this.multiChecked = checked;
            this.multiListener = listener;
            this.singleChoice = false;
            return this;
        }

        Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
            this.positiveText = text;
            this.positiveListener = listener;
            return this;
        }

        Builder setPositiveButton(int resId, DialogInterface.OnClickListener listener) {
            return setPositiveButton(context.getString(resId), listener);
        }

        Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
            this.negativeText = text;
            this.negativeListener = listener;
            return this;
        }

        Builder setNegativeButton(int resId, DialogInterface.OnClickListener listener) {
            return setNegativeButton(context.getString(resId), listener);
        }

        Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
            this.neutralText = text;
            this.neutralListener = listener;
            return this;
        }

        Builder setCancelable(boolean value) {
            this.cancelable = value;
            return this;
        }

        Builder setOnDismissListener(DialogInterface.OnDismissListener listener) {
            this.dismissListener = listener;
            return this;
        }

        Builder kind(int value) {
            this.kind = value;
            return this;
        }

        private int resolveKind() {
            if (kind >= 0) return kind;
            String positive = positiveText == null ? "" : positiveText.toString();
            String heading = title == null ? "" : title.toString();
            if (positive.contains("삭제") || positive.contains("지우") || positive.contains("지울")) return KIND_DANGER;
            // Older call sites answer "삭제할까요?" with the system's "확인": still a deletion.
            if (heading.contains("삭제") || heading.contains("지울")) return KIND_DANGER;
            if (heading.contains("실패") || heading.contains("못했") || heading.contains("오류") || heading.contains("비정상")) return KIND_ERROR;
            if (view != null && hasEditText(view)) return KIND_EDIT;
            if (items != null) return KIND_EDIT;
            if (heading.endsWith("?") || heading.contains("할까요")) return KIND_CONFIRM;
            return KIND_INFO;
        }

        private static boolean hasEditText(View root) {
            if (root instanceof android.widget.EditText) return true;
            if (root instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) root;
                for (int k = 0; k < group.getChildCount(); k++) {
                    if (hasEditText(group.getChildAt(k))) return true;
                }
            }
            return false;
        }

        Dialog create() {
            final Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            int kind = resolveKind();
            float density = context.getResources().getDisplayMetrics().density;
            int dp16 = Math.round(16 * density);

            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(Math.round(20 * density), Math.round(20 * density), Math.round(20 * density), dp16);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.WHITE);
            background.setCornerRadius(22 * density);
            panel.setBackground(background);

            // Header: the round icon and the title beside it.
            if (title != null && title.length() > 0) {
                LinearLayout header = new LinearLayout(context);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(Gravity.CENTER_VERTICAL);
                TextView icon = new TextView(context);
                icon.setText(glyphFor(kind));
                icon.setTextColor(Color.WHITE);
                icon.setTextSize(kind == KIND_INFO ? 18.0f : 17.0f);
                icon.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                icon.setGravity(Gravity.CENTER);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(colorFor(kind));
                icon.setBackground(circle);
                header.addView(icon, new LinearLayout.LayoutParams(Math.round(40 * density), Math.round(40 * density)));
                TextView heading = new TextView(context);
                heading.setText(title);
                heading.setTextSize(16.0f);
                heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                heading.setTextColor(Color.rgb(27, 31, 39));
                LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
                headingParams.setMargins(Math.round(12 * density), 0, 0, 0);
                header.addView(heading, headingParams);
                panel.addView(header);
            }
            if (message != null && message.length() > 0) {
                TextView body = new TextView(context);
                body.setText(message);
                body.setTextSize(13.0f);
                body.setLineSpacing(0, 1.3f);
                body.setTextColor(Color.rgb(55, 65, 81));
                body.setTextIsSelectable(kind == KIND_ERROR);
                body.setPadding(0, Math.round(12 * density), 0, 0);
                panel.addView(body);
            }
            if (view != null) {
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                LinearLayout.LayoutParams viewParams = new LinearLayout.LayoutParams(-1, -2);
                viewParams.setMargins(0, Math.round(10 * density), 0, 0);
                panel.addView(view, viewParams);
            }
            if (items != null) {
                LinearLayout list = new LinearLayout(context);
                list.setOrientation(LinearLayout.VERTICAL);
                for (int k = 0; k < items.length; k++) {
                    final int index = k;
                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(Math.round(10 * density), Math.round(11 * density), Math.round(10 * density), Math.round(11 * density));
                    boolean chosen = singleChoice ? k == checkedItem : multiChecked != null && k < multiChecked.length && multiChecked[k];
                    GradientDrawable rowBackground = new GradientDrawable();
                    rowBackground.setCornerRadius(12 * density);
                    rowBackground.setColor(chosen ? Color.rgb(236, 254, 255) : Color.rgb(246, 247, 249));
                    row.setBackground(rowBackground);
                    final View dot = singleChoice || multiChecked != null ? new View(context) : null;
                    if (dot != null) {
                        GradientDrawable dotShape = new GradientDrawable();
                        if (singleChoice) {
                            dotShape.setShape(GradientDrawable.OVAL);
                        } else {
                            dotShape.setCornerRadius(5 * density);
                        }
                        dotShape.setColor(chosen ? Color.rgb(14, 116, 144) : Color.WHITE);
                        dotShape.setStroke(Math.round(2 * density), chosen ? Color.rgb(14, 116, 144) : Color.rgb(201, 206, 214));
                        dot.setBackground(dotShape);
                        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(Math.round(16 * density), Math.round(16 * density));
                        dotParams.setMargins(0, 0, Math.round(10 * density), 0);
                        row.addView(dot, dotParams);
                    }
                    final TextView label = new TextView(context);
                    label.setText(items[k]);
                    label.setTextSize(13.5f);
                    label.setTextColor(chosen ? Color.rgb(14, 116, 144) : Color.rgb(31, 41, 51));
                    label.setTypeface(chosen ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
                    row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));
                    row.setClickable(true);
                    row.setOnClickListener(v -> {
                        if (multiChecked != null) {
                            // A tick list: toggle and tell the listener; the buttons finish it.
                            if (index < multiChecked.length) {
                                multiChecked[index] = !multiChecked[index];
                                boolean on = multiChecked[index];
                                GradientDrawable boxShape = new GradientDrawable();
                                boxShape.setCornerRadius(5 * density);
                                boxShape.setColor(on ? Color.rgb(14, 116, 144) : Color.WHITE);
                                boxShape.setStroke(Math.round(2 * density), on ? Color.rgb(14, 116, 144) : Color.rgb(201, 206, 214));
                                if (dot != null) dot.setBackground(boxShape);
                                label.setTextColor(on ? Color.rgb(14, 116, 144) : Color.rgb(31, 41, 51));
                                label.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
                                if (multiListener != null) {
                                    multiListener.onClick(dialog, index, on);
                                }
                            }
                            return;
                        }
                        if (itemsListener != null) {
                            itemsListener.onClick(dialog, index);
                        }
                        // Plain item lists close on a tap, as the system dialog does; a
                        // single-choice list leaves that to its listener.
                        if (!singleChoice && dialog.isShowing()) {
                            dialog.dismiss();
                        }
                    });
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                    rowParams.setMargins(0, Math.round((k == 0 ? 12 : 6) * density), 0, 0);
                    list.addView(row, rowParams);
                }
                panel.addView(list);
            }

            // Buttons: cancel and neutral in grey on the left, the main one coloured.
            List<View> buttons = new ArrayList<>();
            if (negativeText != null) {
                buttons.add(button(dialog, negativeText, Color.rgb(241, 242, 244), Color.rgb(75, 85, 99), negativeListener, DialogInterface.BUTTON_NEGATIVE, density));
            }
            if (neutralText != null) {
                buttons.add(button(dialog, neutralText, Color.rgb(241, 242, 244), Color.rgb(75, 85, 99), neutralListener, DialogInterface.BUTTON_NEUTRAL, density));
            }
            if (positiveText != null) {
                int color = kind == KIND_DANGER || kind == KIND_ERROR ? Color.rgb(225, 29, 72) : kind == KIND_CONFIRM ? Color.rgb(242, 153, 74) : Color.rgb(22, 185, 207);
                CharSequence label = positiveText;
                // The button names the deed: a deletion answered with the system's "확인" says 삭제.
                if (kind == KIND_DANGER && label.toString().equals(context.getString(android.R.string.ok))) {
                    label = "삭제";
                }
                buttons.add(button(dialog, label, color, Color.WHITE, positiveListener, DialogInterface.BUTTON_POSITIVE, density));
            }
            if (!buttons.isEmpty()) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int k = 0; k < buttons.size(); k++) {
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Math.round(46 * density), k == buttons.size() - 1 && buttons.size() > 1 ? 1.3f : 1.0f);
                    params.setMargins(k == 0 ? 0 : Math.round(8 * density), 0, 0, 0);
                    row.addView(buttons.get(k), params);
                }
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                rowParams.setMargins(0, dp16, 0, 0);
                panel.addView(row, rowParams);
            }

            ScrollView scroll = new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(panel);
            dialog.setContentView(scroll);
            dialog.setCancelable(cancelable);
            // Deleting is never done by a stray touch beside the card.
            dialog.setCanceledOnTouchOutside(cancelable && kind != KIND_DANGER);
            if (dismissListener != null) {
                dialog.setOnDismissListener(dismissListener);
            }
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0));
                window.setDimAmount(0.45f);
                android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                window.setLayout(Math.min(metrics.widthPixels - Math.round(32 * density), Math.round(380 * density)), WindowManager.LayoutParams.WRAP_CONTENT);
                window.setGravity(Gravity.CENTER);
            }
            return dialog;
        }

        Dialog show() {
            Dialog dialog = create();
            dialog.show();
            return dialog;
        }

        private TextView button(Dialog dialog, CharSequence text, int background, int textColor, DialogInterface.OnClickListener listener, int which, float density) {
            TextView button = new TextView(context);
            button.setText(text);
            button.setTextSize(14.0f);
            button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            button.setTextColor(textColor);
            button.setGravity(Gravity.CENTER);
            button.setMaxLines(1);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(background);
            shape.setCornerRadius(12 * density);
            button.setBackground(shape);
            button.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onClick(dialog, which);
                }
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
            });
            return button;
        }

        private static String glyphFor(int kind) {
            switch (kind) {
                case KIND_DANGER: return "✕";
                case KIND_CONFIRM: return "?";
                case KIND_ERROR: return "!";
                case KIND_EDIT: return "✎";
                default: return "✓";
            }
        }

        private static int colorFor(int kind) {
            switch (kind) {
                case KIND_DANGER: return Color.rgb(225, 29, 72);
                case KIND_CONFIRM: return Color.rgb(242, 153, 74);
                case KIND_ERROR: return Color.rgb(225, 29, 72);
                case KIND_EDIT: return Color.rgb(139, 92, 246);
                default: return Color.rgb(22, 185, 207);
            }
        }
    }
}
