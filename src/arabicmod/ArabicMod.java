package arabicmod;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.freetype.FreeTypeFontGenerator;
import arc.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.Mod;
import mindustry.ui.Fonts;

import java.util.Locale;

/**
 * Arabic RTL support as a proper mod instead of a source/APK patch.
 *
 * Everything here is a RUNTIME override: font glyph data and bundle strings are rewritten in
 * memory after the game finishes loading (see {@link #apply()}). Nothing on disk outside this
 * mod's own folder is ever touched, which means removing/disabling the mod automatically and
 * fully reverts the game to stock behavior -- no backup/restore logic is needed, because nothing
 * original was ever overwritten in the first place.
 *
 * NOTE ON API CERTAINTY: the shaping/reordering logic in {@link ArabicTextUtils} and the
 * font-generation calls below (arc.freetype.FreeTypeFontGenerator) are stable, well-established
 * APIs. The settings UI calls (sliderPref/checkPref/addCategory) below were checked directly
 * against Mindustry's own SettingsMenuDialog.java source to confirm exact argument order --
 * sliderPref specifically takes (name, default, min, max, step, formatter); an earlier draft of
 * this file was missing the step argument. The one thing that genuinely still needs a real build
 * to confirm is whether SettingsTable's exact package location (it was moved from
 * arc.scene.ui.SettingsDialog.SettingsTable into mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable
 * at some point) matches what your specific Mindustry/Arc dependency version resolves to -- if the
 * import doesn't resolve, that's the first thing to check.
 */
public class ArabicMod extends Mod{

    private static final String KEY_ENABLED = "arabicmod-enabled";
    private static final String KEY_FONT = "arabicmod-font";       // chosen .ttf filename
    private static final String KEY_SIZE = "arabicmod-size";       // font size in px, 10-40
    private static final String KEY_BORDER10 = "arabicmod-border"; // outline width in px * 10 (sliders are integer-only), 0-30

    public ArabicMod(){
        // ClientLoadEvent fires once, after the game has fully finished its own startup loading
        // (including the stock font/bundle setup) -- applying our overrides here means we always
        // run after, and therefore win over, anything stock loadDefaultFont()/loadSettings() did.
        Events.on(ClientLoadEvent.class, e -> apply());
    }

    @Override
    public void init(){
        Vars.ui.settings.addCategory("Arabic Support", table -> {
            table.checkPref(KEY_ENABLED, true);
            table.row();

            table.sliderPref(KEY_SIZE, 18, 10, 40, 1, i -> i + "px (font size)");
            table.sliderPref(KEY_BORDER10, 10, 0, 30, 1, i -> (i / 10f) + "px (outline width)");

            table.row();
            table.button("Open font folder", this::openFontFolder).pad(4f);
            table.row();
            table.add(
                "Drop any .ttf file into that folder to add it to the list below. " +
                "The font must have glyphs for Arabic Presentation Forms-B (U+FE70-U+FEFF), " +
                "or letters will render blank -- Noto Sans/Naskh Arabic, Amiri, and Cairo are " +
                "known-good choices. Changes apply on next game restart."
            ).wrap().width(420f).left();

            table.row();
            for(Fi font : availableFonts()){
                boolean selected = font.name().equals(Core.settings.getString(KEY_FONT, "font_ar.ttf"));
                table.button((selected ? "> " : "") + font.nameWithoutExtension(),
                    () -> Core.settings.put(KEY_FONT, font.name())).pad(4f).row();
            }
        });
    }

    private void openFontFolder(){
        Fi folder = fontFolder();
        // arc.util.OS / Core.app may expose a direct "open folder" call depending on platform and
        // Arc version; falling back to just printing the path is always safe if that call isn't
        // available in your version.
        try{
            Core.app.openFolder(folder.absolutePath());
        }catch(Throwable t){
            arc.util.Log.info("Custom fonts go in: @", folder.absolutePath());
        }
    }

    private Fi fontFolder(){
        Fi folder = getConfigFolder().child("fonts");
        folder.mkdirs();
        return folder;
    }

    /** The mod's own bundled default font, plus anything the user has dropped into the config folder. */
    private Seq<Fi> availableFonts(){
        Seq<Fi> out = new Seq<>();
        Fi bundled = Core.files.internal("fonts/font_ar.ttf");
        if(bundled.exists()) out.add(bundled);
        out.addAll(fontFolder().findAll(f -> f.extension().equalsIgnoreCase("ttf")));
        return out;
    }

    private Fi selectedFont(){
        String name = Core.settings.getString(KEY_FONT, "font_ar.ttf");
        Seq<Fi> fonts = availableFonts();
        for(Fi f : fonts){
            if(f.name().equals(name)) return f;
        }
        return fonts.isEmpty() ? null : fonts.first();
    }

    private void apply(){
        if(!Core.settings.getBool(KEY_ENABLED, true)) return;
        if(!Locale.getDefault().getLanguage().equals("ar")) return;

        ArabicTextUtils.reshapeBundle(Core.bundle);

        Fi font = selectedFont();
        if(font == null || !font.exists()){
            arc.util.Log.warn("[arabic-support] No Arabic font file found -- Arabic text will use "
                + "whatever glyphs the default font happens to have (likely none).");
            return;
        }

        int size = Core.settings.getInt(KEY_SIZE, 18);
        float border = Core.settings.getInt(KEY_BORDER10, 10) / 10f;

        // Generating fonts directly here (instead of going through Core.assets.load(...,
        // Font.class, ...) like the stock APK patch did) deliberately bypasses Mindustry's shared
        // FreetypeFontLoader -- which forces borderWidth=2px on any asset whose name ends in
        // "outline", with no per-language exception. Generating directly means our own borderWidth
        // setting always takes effect, with no naming trick or engine patch needed.
        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(font);

        Font base = gen.generateFont(new FreeTypeFontParameter(){{
            this.size = size;
            incremental = true;
            shadowColor = Color.darkGray;
            shadowOffsetY = 2;
            characters = "\u0000 ";
        }});
        Fonts.def.data.setOverride(base.data);

        Font bordered = gen.generateFont(new FreeTypeFontParameter(){{
            this.size = size;
            incremental = true;
            borderColor = Color.darkGray;
            this.borderWidth = border;
            characters = "\u0000 ";
        }});
        Fonts.outline.data.setOverride(bordered.data);

        gen.dispose();
    }
}
