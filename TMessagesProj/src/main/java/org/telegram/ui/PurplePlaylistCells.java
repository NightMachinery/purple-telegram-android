package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

final class PurplePlaylistCells {
    private static int color(int key, Theme.ResourcesProvider provider) {
        return Theme.getColor(key, provider);
    }

    private static LinearLayout column(Context context) {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private static TextView label(Context context, int size, boolean bold, int lines) {
        TextView result = new TextView(context);
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) result.setTypeface(AndroidUtilities.bold());
        result.setMaxLines(lines);
        result.setEllipsize(TextUtils.TruncateAt.END);
        result.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return result;
    }

    private static void direction(View view) {
        view.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
    }

    private static GradientDrawable shape(int radius, int first, int second) {
        GradientDrawable result = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{first, second});
        result.setCornerRadius(dp(radius));
        return result;
    }

    private static final class Sleeve extends FrameLayout {
        private final Paint rings = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ImageView note;

        Sleeve(Context context) {
            super(context);
            setClipToOutline(true);
            setWillNotDraw(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            rings.setStyle(Paint.Style.STROKE);
            note = new ImageView(context);
            note.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(note, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        }

        void bind(int seed, int glyph, int glyphSize, Theme.ResourcesProvider provider) {
            int index = (seed & Integer.MAX_VALUE) % Theme.keys_avatar_background.length;
            int ink = color(Theme.key_avatar_text, provider);
            setBackground(shape(12, color(Theme.keys_avatar_background[index], provider),
                    color(Theme.keys_avatar_background2[index], provider)));
            rings.setColor(Theme.multAlpha(ink, .22f));
            rings.setStrokeWidth(dp(1));
            note.setImageResource(glyph);
            note.setColorFilter(ink);
            note.setLayoutParams(LayoutHelper.createFrame(glyphSize, glyphSize, Gravity.CENTER));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth(), getHeight());
            for (int i = 0; i < 3; i++) {
                canvas.drawCircle(getWidth() * .90f, getHeight() * .87f, size * (.36f + i * .11f), rings);
            }
        }
    }

    static final class Row extends LinearLayout {
        final Sleeve sleeve;
        final TextView title, subtitle, duration;
        final Theme.ResourcesProvider provider;
        final View rule;
        final LinearLayout line;

        Row(Context context, Theme.ResourcesProvider provider) {
            super(context);
            this.provider = provider;
            setOrientation(VERTICAL);
            direction(this);
            line = new LinearLayout(context);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(18), dp(9), dp(18), dp(9));
            addView(line, LayoutHelper.createLinear(-1, -2));
            sleeve = new Sleeve(context);
            line.addView(sleeve, LayoutHelper.createLinear(44, 44));
            LinearLayout labels = column(context);
            labels.setPaddingRelative(dp(14), 0, dp(8), 0);
            line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            labels.addView(title = label(context, 16, false, 2));
            labels.addView(subtitle = label(context, 14, false, 2));
            duration = label(context, 13, false, 1);
            line.addView(duration, LayoutHelper.createLinear(-2, -2));
            rule = new View(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
            lp.setMarginStart(dp(76));
            addView(rule, lp);
        }
    }

    static final class RowFactory extends UItem.UItemFactory<Row> {
        static { setup(new RowFactory()); }

        static UItem of(int id, String key, Object model, String title, String subtitle, String duration, int seed, boolean selected) {
            UItem item = UItem.ofFactory(RowFactory.class);
            item.id = id;
            item.object = key;
            item.object2 = model;
            item.text = title;
            item.subtext = subtitle;
            item.textValue = duration;
            item.intValue = seed;
            item.checked = selected;
            return item;
        }

        @Override public boolean equals(UItem a, UItem b) {
            return a.id == b.id && a.object.equals(b.object);
        }

        @Override public boolean contentsEquals(UItem a, UItem b) {
            return a.itemEquals(b) && a.checked == b.checked && a.enabled == b.enabled;
        }

        @Override public Row createView(Context context, RecyclerListView list, int account, int guid, Theme.ResourcesProvider provider) {
            return new Row(context, provider);
        }

        @Override public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView list) {
            Row row = (Row) view;
            Theme.ResourcesProvider provider = row.provider;
            int primary = color(item.checked ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteBlackText, provider);
            int secondary = color(Theme.key_windowBackgroundWhiteGrayText2, provider);
            row.sleeve.bind(item.intValue, item.checked ? R.drawable.ic_play : R.drawable.msg_filled_data_music, 23, provider);
            row.title.setTextColor(primary);
            row.title.setText(item.text);
            row.subtitle.setTextColor(secondary);
            row.subtitle.setText(item.subtext);
            row.duration.setTextColor(secondary);
            row.duration.setText(item.textValue);
            row.duration.setVisibility(TextUtils.isEmpty(item.textValue) ? View.GONE : View.VISIBLE);
            row.duration.setTextDirection(View.TEXT_DIRECTION_LTR);
            row.rule.setBackgroundColor(color(Theme.key_divider, provider));
            row.rule.setVisibility(divider ? View.VISIBLE : View.INVISIBLE);
            row.line.setAlpha(item.enabled ? 1f : .58f);
            row.setContentDescription(TextUtils.join(", ", TextUtils.isEmpty(item.textValue)
                    ? new CharSequence[]{item.text, item.subtext} : new CharSequence[]{item.text, item.subtext, item.textValue}));
        }
    }

    static final class Deck extends LinearLayout {
        final LinearLayout body;
        final TextView eyebrow, title, subtitle;
        final Sleeve sleeve;
        final ImageView transport, artwork;
        final PlayPauseDrawable playPause = new PlayPauseDrawable(21);

        Deck(Context context) {
            super(context);
            direction(this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(12), dp(16), dp(12));
            body = new LinearLayout(context);
            body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPadding(dp(10), dp(12), dp(6), dp(12));
            body.setFocusable(true);
            addView(body, new LinearLayout.LayoutParams(0, -2, 1));
            sleeve = new Sleeve(context);
            artwork = new ImageView(context);
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            sleeve.addView(artwork, LayoutHelper.createFrame(-1, -1));
            body.addView(sleeve, LayoutHelper.createLinear(54, 54));
            LinearLayout labels = column(context);
            labels.setPaddingRelative(dp(12), 0, 0, 0);
            body.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            labels.addView(eyebrow = label(context, 12, true, 1));
            labels.addView(title = label(context, 17, true, 2));
            labels.addView(subtitle = label(context, 14, false, 1));
            transport = new ImageView(context);
            transport.setScaleType(ImageView.ScaleType.CENTER);
            transport.setImageDrawable(playPause);
            transport.setFocusable(true);
            LinearLayout.LayoutParams lp = LayoutHelper.createLinear(48, 48);
            lp.setMarginStart(dp(6));
            addView(transport, lp);
        }

        void bind(String name, String artist, int seed, android.graphics.Bitmap cover, boolean paused, Runnable open, Runnable toggle, Theme.ResourcesProvider provider) {
            int accent = color(Theme.key_windowBackgroundWhiteBlueText, provider);
            int base = color(Theme.key_windowBackgroundWhite, provider);
            int tint = ColorUtils.blendARGB(base, accent, .085f);
            body.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), tint, color(Theme.key_listSelector, provider)));
            body.setOnClickListener(v -> open.run());
            String status = PurplePlaylistsActivity.text(paused ? "PlaybackPaused" : "NowPlaying");
            eyebrow.setText(status);
            eyebrow.setTextColor(accent);
            title.setText(name);
            title.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText, provider));
            subtitle.setText(artist);
            subtitle.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText2, provider));
            body.setContentDescription(PurplePlaylistsActivity.text("OpenPlayer") + ", " + name + ", " + artist + ", " + status);
            sleeve.bind(seed, R.drawable.msg_filled_data_music, 28, provider);
            artwork.setImageBitmap(cover);
            artwork.setVisibility(cover == null ? GONE : VISIBLE);
            transport.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(24), tint, color(Theme.key_listSelector, provider)));
            playPause.setColor(accent);
            playPause.setPause(!paused, false);
            transport.setContentDescription(getString(paused ? R.string.AccActionPlay : R.string.AccActionPause));
            transport.setOnClickListener(v -> toggle.run());
        }
    }

    static View empty(Context context, Theme.ResourcesProvider provider, int icon, String title, String hint, Runnable create) {
        LinearLayout box = column(context);
        direction(box);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(40), dp(28), dp(32));
        FrameLayout art = new FrameLayout(context);
        box.addView(art, LayoutHelper.createLinear(122, 98));
        int accent = color(Theme.key_windowBackgroundWhiteBlueText, provider);
        int base = color(Theme.key_windowBackgroundWhite, provider);
        View back = new View(context);
        back.setRotation(-12f);
        back.setBackground(shape(14, ColorUtils.blendARGB(base, accent, .33f), ColorUtils.blendARGB(base, accent, .18f)));
        art.addView(back, LayoutHelper.createFrame(68, 68, Gravity.CENTER, 0, 0, 24, 8));
        Sleeve front = new Sleeve(context);
        front.bind(5, icon, 34, provider);
        front.setRotation(7f);
        art.addView(front, LayoutHelper.createFrame(72, 72, Gravity.CENTER, 16, 4, 0, 0));
        TextView heading = label(context, 22, true, 3);
        heading.setGravity(Gravity.CENTER);
        heading.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText, provider));
        heading.setText(title);
        heading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        box.addView(heading, LayoutHelper.createLinear(-1, -2, 0, 16, 0, 0));
        TextView help = label(context, 15, false, 6);
        help.setGravity(Gravity.CENTER);
        help.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText2, provider));
        help.setText(hint);
        help.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        box.addView(help, LayoutHelper.createLinear(-1, -2, 0, 6, 0, 0));
        if (create != null) {
            ButtonWithCounterView button = new ButtonWithCounterView(context, provider);
            button.setText(PurplePlaylistsActivity.text("Create"), false);
            button.setContentDescription(PurplePlaylistsActivity.text("Create"));
            button.setOnClickListener(v -> create.run());
            box.addView(button, LayoutHelper.createLinear(-1, 48, 8, 24, 8, 0));
        }
        return box;
    }

    private PurplePlaylistCells() { }
}
