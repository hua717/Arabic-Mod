package arabicmod;

import arc.struct.ObjectMap;
import arc.util.I18NBundle;

import java.util.ArrayList;

/**
 * Utilities that make Arabic-language bundle text render correctly in Mindustry's UI, given that
 * Arc has no built-in Arabic shaping or RTL support.
 *
 * <p>This is the mod-packaged version of the same utility originally written directly into
 * mindustry.i18n as a source patch. Functionally identical, plus four bug fixes found during
 * real-device testing (see inline comments below marked FIX 1-4).
 *
 * <p><b>Why this is NOT a subclass/wrapper of I18NBundle:</b> {@code I18NBundle#get(String)} is
 * {@code public final} and {@code createBundle(...)} has no factory hook to return a subclass, so
 * a wrapper-based "ArabicI18NBundle" isn't achievable without forking arc's I18NBundle itself.
 * Instead, {@link #reshapeBundle(I18NBundle)} rewrites the bundle's own mutable properties map
 * (via {@code I18NBundle#getProperties()}) once, right after the bundle loads. Every later
 * {@code bundle.get(key)} / {@code bundle.format(key, args)} call then "just works".
 *
 * <p><b>Known limitation:</b> {@code format(key, args)} calls where the substituted argument is
 * itself Arabic text needing bidi participation with the template aren't reordered here -- that
 * needs a hook at draw time, after substitution. {@link #process(String)} is a ready entry point
 * for whenever that hook is added.
 */
public final class ArabicTextUtils{
    private ArabicTextUtils(){}

    private static final int ISOLATED = 0, INITIAL = 1, MEDIAL = 2, FINAL = 3;

    private static final ObjectMap<Character, String[]> SHAPES = new ObjectMap<>();
    private static final ObjectMap<Character, String[]> LAM_LIGATURES = new ObjectMap<>();
    private static final ObjectMap<Character, Character> MIRROR = new ObjectMap<>();

    static{
        put('\u0621', "\uFE80", "", "", "");
        put('\u0622', "\uFE81", "", "", "\uFE82");
        put('\u0623', "\uFE83", "", "", "\uFE84");
        put('\u0624', "\uFE85", "", "", "\uFE86");
        put('\u0625', "\uFE87", "", "", "\uFE88");
        put('\u0626', "\uFE89", "\uFE8B", "\uFE8C", "\uFE8A");
        put('\u0627', "\uFE8D", "", "", "\uFE8E");
        put('\u0628', "\uFE8F", "\uFE91", "\uFE92", "\uFE90");
        put('\u0629', "\uFE93", "", "", "\uFE94");
        put('\u062A', "\uFE95", "\uFE97", "\uFE98", "\uFE96");
        put('\u062B', "\uFE99", "\uFE9B", "\uFE9C", "\uFE9A");
        put('\u062C', "\uFE9D", "\uFE9F", "\uFEA0", "\uFE9E");
        put('\u062D', "\uFEA1", "\uFEA3", "\uFEA4", "\uFEA2");
        put('\u062E', "\uFEA5", "\uFEA7", "\uFEA8", "\uFEA6");
        put('\u062F', "\uFEA9", "", "", "\uFEAA");
        put('\u0630', "\uFEAB", "", "", "\uFEAC");
        put('\u0631', "\uFEAD", "", "", "\uFEAE");
        put('\u0632', "\uFEAF", "", "", "\uFEB0");
        put('\u0633', "\uFEB1", "\uFEB3", "\uFEB4", "\uFEB2");
        put('\u0634', "\uFEB5", "\uFEB7", "\uFEB8", "\uFEB6");
        put('\u0635', "\uFEB9", "\uFEBB", "\uFEBC", "\uFEBA");
        put('\u0636', "\uFEBD", "\uFEBF", "\uFEC0", "\uFEBE");
        put('\u0637', "\uFEC1", "\uFEC3", "\uFEC4", "\uFEC2");
        put('\u0638', "\uFEC5", "\uFEC7", "\uFEC8", "\uFEC6");
        put('\u0639', "\uFEC9", "\uFECB", "\uFECC", "\uFECA");
        put('\u063A', "\uFECD", "\uFECF", "\uFED0", "\uFECE");
        put('\u0640', "\u0640", "\u0640", "\u0640", "\u0640");
        put('\u0641', "\uFED1", "\uFED3", "\uFED4", "\uFED2");
        put('\u0642', "\uFED5", "\uFED7", "\uFED8", "\uFED6");
        put('\u0643', "\uFED9", "\uFEDB", "\uFEDC", "\uFEDA");
        put('\u0644', "\uFEDD", "\uFEDF", "\uFEE0", "\uFEDE");
        put('\u0645', "\uFEE1", "\uFEE3", "\uFEE4", "\uFEE2");
        put('\u0646', "\uFEE5", "\uFEE7", "\uFEE8", "\uFEE6");
        put('\u0647', "\uFEE9", "\uFEEB", "\uFEEC", "\uFEEA");
        put('\u0648', "\uFEED", "", "", "\uFEEE");
        put('\u0649', "\uFEEF", "\uFBE8", "\uFBE9", "\uFEF0");
        put('\u064A', "\uFEF1", "\uFEF3", "\uFEF4", "\uFEF2");

        LAM_LIGATURES.put('\u0627', new String[]{"\uFEFB", "\uFEFC"});
        LAM_LIGATURES.put('\u0622', new String[]{"\uFEF5", "\uFEF6"});
        LAM_LIGATURES.put('\u0623', new String[]{"\uFEF7", "\uFEF8"});
        LAM_LIGATURES.put('\u0625', new String[]{"\uFEF9", "\uFEFA"});

        MIRROR.put('(', ')'); MIRROR.put(')', '(');
        MIRROR.put('[', ']'); MIRROR.put(']', '[');
        MIRROR.put('{', '}'); MIRROR.put('}', '{');
        MIRROR.put('<', '>'); MIRROR.put('>', '<');
    }

    private static void put(char base, String iso, String init, String med, String fin){
        SHAPES.put(base, new String[]{iso, init, med, fin});
    }

    private static boolean isArabicChar(char c){
        return (c >= '\u0600' && c <= '\u06FF') || (c >= '\u0750' && c <= '\u077F')
            || (c >= '\uFB50' && c <= '\uFDFF') || (c >= '\uFE70' && c <= '\uFEFF');
    }

    public static boolean isArabic(String text){
        if(text == null) return false;
        for(int i = 0; i < text.length(); i++){
            if(isArabicChar(text.charAt(i))) return true;
        }
        return false;
    }

    private static boolean isShapable(char c){
        return SHAPES.containsKey(c);
    }

    private static boolean canConnectNext(char c){
        String[] f = SHAPES.get(c);
        return f != null && !f[INITIAL].isEmpty();
    }

    private static boolean canConnectPrev(char c){
        String[] f = SHAPES.get(c);
        return f != null && !f[FINAL].isEmpty();
    }

    public static String shape(String text){
        if(text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());

        for(int i = 0; i < text.length(); i++){
            char c = text.charAt(i);

            if(!isShapable(c)){
                out.append(c);
                continue;
            }

            boolean connectFromPrev = i > 0 && isShapable(text.charAt(i - 1)) && canConnectNext(text.charAt(i - 1));

            if(c == '\u0644' && i + 1 < text.length() && LAM_LIGATURES.containsKey(text.charAt(i + 1))){
                String[] forms = LAM_LIGATURES.get(text.charAt(i + 1));
                out.append(connectFromPrev ? forms[1] : forms[0]);
                i++;
                continue;
            }

            boolean nextIsLetter = i + 1 < text.length() && isShapable(text.charAt(i + 1));
            boolean connectToNext = nextIsLetter && canConnectNext(c) && canConnectPrev(text.charAt(i + 1));

            String[] forms = SHAPES.get(c);
            int form = connectFromPrev && connectToNext ? MEDIAL
                : connectFromPrev ? FINAL
                : connectToNext ? INITIAL
                : ISOLATED;

            String glyph = forms[form];
            out.append(glyph.isEmpty() ? forms[ISOLATED] : glyph);
        }

        return out.toString();
    }

    /**
     * Simplified bidi reordering. See class doc for why this isn't a full UAX #9 implementation.
     */
    public static String reorderVisual(String shaped){
        if(shaped == null || shaped.isEmpty()) return shaped;

        int n = shaped.length();
        char[] type = new char[n]; // 'R' = arabic, 'L' = latin/digit/markup, 'N' = neutral (resolved below)

        for(int i = 0; i < n; i++){
            char c = shaped.charAt(i);
            if(isArabicChar(c)){
                type[i] = 'R';
            }else if(Character.isLetter(c) || Character.isDigit(c)
                // FIX 1+2+3: '{' '}' (format placeholders), '[' ']' (Mindustry [color] markup,
                // also mirrored -- see MIRROR table above), and ':' (Mindustry :icon: syntax) must
                // never be treated as neutral. A neutral touching Arabic text on one side gets
                // absorbed into that run and reversed/mirrored along with it, which silently
                // corrupts these literal tokens (e.g. "{0}" -> "}0{", "[accent]" flipped, or the
                // colons in ":turbine-condenser:" drifting out of position). Classifying them as a
                // strong LTR type instead means they're never absorbed into an Arabic run.
                || c == '{' || c == '}' || c == '[' || c == ']' || c == ':'){
                type[i] = 'L';
            }else{
                type[i] = 'N';
            }
        }

        // resolve neutral runs (spaces, punctuation) using their strong neighbors.
        for(int i = 0; i < n; i++){
            if(type[i] != 'N') continue;
            int end = i;
            while(end < n && type[end] == 'N') end++;
            char before = i > 0 ? type[i - 1] : 0;
            char after = end < n ? type[end] : 0;

            // FIX 4: a neutral should only inherit a shared type when BOTH neighbors agree.
            // The original version always preferred "before" unconditionally, which glued
            // boundary spaces to the wrong side whenever "before" and "after" disagreed (e.g. a
            // space between a now-LTR "[]" token and a following Arabic word was wrongly stuck to
            // "[]" instead of the Arabic word, swallowing the visible gap between them). Correct
            // bidi behavior: if neighbors differ, default to the paragraph direction (R here).
            char resolved;
            if(before != 0 && after != 0){
                resolved = before == after ? before : 'R';
            }else if(before != 0){
                resolved = before;
            }else if(after != 0){
                resolved = after;
            }else{
                resolved = 'R';
            }

            for(int k = i; k < end; k++) type[k] = resolved;
            i = end - 1;
        }

        // group into runs of matching resolved type, reversing characters (and mirroring paired
        // punctuation) within each right-to-left run.
        ArrayList<String> runs = new ArrayList<>();
        int start = 0;
        for(int i = 1; i <= n; i++){
            if(i == n || type[i] != type[start]){
                String run = shaped.substring(start, i);
                if(type[start] == 'R'){
                    StringBuilder rev = new StringBuilder(run.length());
                    for(int k = run.length() - 1; k >= 0; k--){
                        char c = run.charAt(k);
                        rev.append(MIRROR.containsKey(c) ? MIRROR.get(c) : c);
                    }
                    run = rev.toString();
                }
                runs.add(run);
                start = i;
            }
        }

        StringBuilder result = new StringBuilder(n);
        for(int i = runs.size() - 1; i >= 0; i--) result.append(runs.get(i));
        return result.toString();
    }

    public static String process(String text){
        return reorderVisual(shape(text));
    }

    public static void reshapeBundle(I18NBundle bundle){
        if(bundle == null) return;
        reshapeProperties(bundle.getProperties());
        reshapeBundle(bundle.getParent());
    }

    private static void reshapeProperties(ObjectMap<String, String> props){
        if(props == null) return;
        for(String key : props.keys()){
            String value = props.get(key);
            if(!isArabic(value)) continue;
            String shaped = shape(value);
            // FIX 1 (continued): now that '{' '}' '[' ']' ':' are protected, reorderVisual is safe
            // to run unconditionally -- no more skipping it for strings with placeholders.
            props.put(key, reorderVisual(shaped));
        }
    }
}
