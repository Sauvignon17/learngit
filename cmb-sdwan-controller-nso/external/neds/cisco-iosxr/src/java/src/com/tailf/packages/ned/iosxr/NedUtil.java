/**
 * Utility class for cisco-ios, cisco-iosxr, cisco-asa and huawei-vrp.
 *
 * @author lbang
 * @version 20180202
 */

package com.tailf.packages.ned.iosxr;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.text.StringCharacterIterator;
import java.text.CharacterIterator;

public final class NedUtil {

    private NedUtil() {}

    public static String getMatch(String text, String regexp) {
        Pattern pattern = Pattern.compile(regexp);
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find())
            return null;
        return matcher.group(1);
    }

    private static String[] getMatches0(String text, String regex, Pattern p) {
        Matcher m;
        try {
            m = p.matcher(text);
        } catch (Exception e) {
            return null;
        }
        if (!m.find())
            return null;
        String[] matches = new String[m.groupCount()+1];
        matches[0] = ""+m.groupCount();
        for (int i = 1; i <= m.groupCount(); i++)
            matches[i] = m.group(i);
        return matches;
    }

    public static String[] getMatches(String text, String regex, int flags) {
        Pattern p = Pattern.compile(regex, flags);
        return getMatches0(text, regex, p);
    }

    public static String[] getMatches(String text, String regex) {
        Pattern p = Pattern.compile(regex);
        return getMatches0(text, regex, p);
    }

    public static String[] fillGroups(Matcher matcher) {
        String[] groups = new String[matcher.groupCount()+1];
        for (int i = 0; i < matcher.groupCount()+1; i++) {
            groups[i] = matcher.group(i);
        }
        return groups;
    }

    public static int indexOf(Pattern pattern, String s, int start) {
        Matcher matcher = pattern.matcher(s);
        return matcher.find(start) ? matcher.start() : -1;
    }

    public static int findString(String search, String text) {
        return indexOf(Pattern.compile(search), text, 0);
    }

    public static String linesToString(String lines[]) {
        StringBuilder string = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            if (lines[n].isEmpty())
                continue;
            string.append(lines[n]+"\n");
        }
        return "\n" + string.toString() + "\n";
    }

    public static String stringQuote(String aText) {
        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char character =  iterator.current();
        result.append("\"");
        while (character != CharacterIterator.DONE ){
            if (character == '"')
                result.append("\\\"");
            else if (character == '\\')
                result.append("\\\\");
            else if (character == '\b')
                result.append("\\b");
            else if (character == '\n')
                result.append("\\n");
            else if (character == '\r')
                result.append("\\r");
            else if (character == (char) 11) // \v
                result.append("\\v");
            else if (character == '\f')
                result.append("'\f");
            else if (character == '\t')
                result.append("\\t");
            else if (character == (char) 27) // \e
                result.append("\\e");
            else
                // The char is not a special one, add it to the result as is
                result.append(character);
            character = iterator.next();
        }
        result.append("\"");
        return result.toString();
    }

    public static String stringDequote(String aText) {
        if (aText.indexOf("\"") != 0) {
            return aText;
        }
        aText = aText.substring(1,aText.length()-1);
        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char c1 = iterator.current();
        while (c1 != CharacterIterator.DONE) {
            if (c1 == '\\') {
                char c2 = iterator.next();
                if (c2 == CharacterIterator.DONE )
                    result.append(c1);
                else if (c2 == 'b')
                    result.append('\b');
                else if (c2 == 'n')
                    result.append('\n');
                else if (c2 == 'r')
                    result.append('\r');
                else if (c2 == 'v')
                    result.append((char) 11); // \v
                else if (c2 == 'f')
                    result.append('\f');
                else if (c2 == 't')
                    result.append('\t');
                else if (c2 == 'e')
                    result.append((char) 27); // \e
                else {
                    result.append(c2);
                }
            }
            else {
                result.append(c1);
            }
            c1 = iterator.next();
        }
        return result.toString();
    }

    public static String passwordQuote(String aText) {
        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char character =  iterator.current();
        while (character != CharacterIterator.DONE ){
            if (character == '"')
                result.append("\\\"");
            else if (character == '\\')
                result.append("\\\\");
            else {
                // The char is not a special one, add it to the result as is
                result.append(character);
            }
            character = iterator.next();
        }
        return result.toString();
    }

    public static String passwordDequote(String aText) {
        if (aText.indexOf("\"") != 0) {
            return aText;
        }
        aText = aText.substring(1,aText.length()-1); // strip ""
        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char c1 = iterator.current();
        while (c1 != CharacterIterator.DONE) {
            if (c1 == '\\') {
                char c2 = iterator.next();
                if (c2 == CharacterIterator.DONE )
                    result.append(c1);
                else if (c2 == '\\')
                    result.append('\\');
                else if (c2 == '\"')
                    result.append('\"');
                else {
                    result.append(c1);
                    result.append(c2);
                }
            }
            else {
                result.append(c1);
            }
            c1 = iterator.next();
        }
        return result.toString();
    }

}
