/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.twitter.Pref;
import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.settings.SettingsStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class TimelineMuteFilter {

    private static String cachedWordsRaw = null;
    private static String cachedUsersRaw = null;
    private static boolean cachedRegexMode = false;

    private static final List<String> cachedWordsList = new ArrayList<>();
    private static final List<Pattern> cachedWordsPatterns = new ArrayList<>();
    private static final List<String> cachedUsersList = new ArrayList<>();

    private static synchronized void updateCacheIfNeeded() {
        String wordsRaw = Pref.timelineMutedWords();
        String usersRaw = Pref.timelineMutedUsers();
        boolean regexMode = Pref.timelineMuteRegex();

        if (wordsRaw == null) wordsRaw = "";
        if (usersRaw == null) usersRaw = "";

        if (wordsRaw.equals(cachedWordsRaw) && usersRaw.equals(cachedUsersRaw) && regexMode == cachedRegexMode) {
            return;
        }

        cachedWordsRaw = wordsRaw;
        cachedUsersRaw = usersRaw;
        cachedRegexMode = regexMode;

        cachedWordsList.clear();
        cachedWordsPatterns.clear();
        cachedUsersList.clear();

        // 1. NGワードのキャッシュ更新
        if (!wordsRaw.isEmpty()) {
            String[] tokens = wordsRaw.split("[,\\n]");
            for (String token : tokens) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    if (regexMode) {
                        try {
                            cachedWordsPatterns.add(Pattern.compile(trimmed, Pattern.CASE_INSENSITIVE));
                        } catch (Exception e) {
                            cachedWordsList.add(trimmed.toLowerCase());
                        }
                    } else {
                        cachedWordsList.add(trimmed.toLowerCase());
                    }
                }
            }
        }

        // 2. NGユーザーのキャッシュ更新
        if (!usersRaw.isEmpty()) {
            String[] tokens = usersRaw.split("[,\\n]");
            for (String token : tokens) {
                String trimmed = token.trim().replace("@", "");
                if (!trimmed.isEmpty()) {
                    cachedUsersList.add(trimmed.toLowerCase());
                }
            }
        }
    }

    // リポスト関連のキャッシュ
    private static volatile boolean reflectionAttempted = false;
    private static java.lang.reflect.Field cachedDirectRetweetField = null;
    private static java.lang.reflect.Field cachedTweetMetadataField = null;
    private static java.lang.reflect.Field cachedSubRetweetMetadataField = null;
    private static final List<java.lang.reflect.Field> cachedRetweeterStringFields = new ArrayList<>();

    private static boolean isRetweetMetadataClass(Class<?> cls) {
        if (cls == null || cls.isPrimitive() || cls.isArray()) return false;
        String name = cls.getName();
        if (name.startsWith("android.") || name.startsWith("java.") || name.startsWith("androidx.") || name.startsWith("kotlin.")) {
            return false;
        }

        java.lang.reflect.Field[] fields = cls.getDeclaredFields();
        if (fields.length < 3 || fields.length > 12) return false;

        int longCount = 0;
        int stringCount = 0;
        int otherCount = 0;

        for (java.lang.reflect.Field f : fields) {
            Class<?> t = f.getType();
            if (t == long.class || t == Long.class) {
                longCount++;
            } else if (t == String.class) {
                stringCount++;
            } else if (t.isPrimitive()) {
                // boolean, int, etc.
            } else {
                otherCount++;
            }
        }

        return (longCount >= 2 && stringCount >= 1 && otherCount <= 2);
    }

    private static void cacheStringFields(Class<?> retweetClass) {
        cachedRetweeterStringFields.clear();
        for (java.lang.reflect.Field f : retweetClass.getDeclaredFields()) {
            if (f.getType() == String.class) {
                f.setAccessible(true);
                cachedRetweeterStringFields.add(f);
            }
        }
    }

    private static synchronized void resolveRetweetFields(Object tweetObj) {
        if (reflectionAttempted) return;

        try {
            Class<?> tweetClass = tweetObj.getClass();

            // 1. Direct fields of tweetObj
            for (java.lang.reflect.Field f : tweetClass.getDeclaredFields()) {
                f.setAccessible(true);
                Class<?> fieldType = f.getType();
                if (isRetweetMetadataClass(fieldType)) {
                    cachedDirectRetweetField = f;
                    cacheStringFields(fieldType);
                    reflectionAttempted = true;
                    return;
                }
            }

            // 2. Child object fields (e.g. Tweet metadata object 'a')
            for (java.lang.reflect.Field f1 : tweetClass.getDeclaredFields()) {
                f1.setAccessible(true);
                Class<?> childClass = f1.getType();
                if (childClass == Object.class) {
                    try {
                        Object childObj = f1.get(tweetObj);
                        if (childObj != null) childClass = childObj.getClass();
                    } catch (Exception ignored) {}
                }

                String childName = childClass.getName();
                if (!childName.startsWith("android.") && !childName.startsWith("java.") && !childName.startsWith("androidx.")) {
                    for (java.lang.reflect.Field f2 : childClass.getDeclaredFields()) {
                        f2.setAccessible(true);
                        Class<?> targetClass = f2.getType();
                        if (targetClass == Object.class) {
                            try {
                                Object childObj = f1.get(tweetObj);
                                if (childObj != null) {
                                    Object grandChild = f2.get(childObj);
                                    if (grandChild != null) targetClass = grandChild.getClass();
                                }
                            } catch (Exception ignored) {}
                        }

                        if (isRetweetMetadataClass(targetClass)) {
                            cachedTweetMetadataField = f1;
                            cachedSubRetweetMetadataField = f2;
                            cacheStringFields(targetClass);
                            reflectionAttempted = true;
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "TimelineMuteFilter.resolveRetweetFields error", e);
        }
    }

    private static List<String> getRetweeterUsernames(Object tweetObj) {
        if (tweetObj == null) return null;

        if (!reflectionAttempted) {
            resolveRetweetFields(tweetObj);
        }

        try {
            Object retweetMeta = null;
            if (cachedDirectRetweetField != null) {
                retweetMeta = cachedDirectRetweetField.get(tweetObj);
            } else if (cachedTweetMetadataField != null && cachedSubRetweetMetadataField != null) {
                Object meta = cachedTweetMetadataField.get(tweetObj);
                if (meta != null) {
                    retweetMeta = cachedSubRetweetMetadataField.get(meta);
                }
            }

            if (retweetMeta == null) {
                return null;
            }

            List<String> usernames = new ArrayList<>();
            for (java.lang.reflect.Field f : cachedRetweeterStringFields) {
                Object val = f.get(retweetMeta);
                if (val instanceof String) {
                    String str = (String) val;
                    if (!str.isEmpty()) {
                        usernames.add(str);
                    }
                }
            }

            // Fallback: check method D() which returns retweeter username if retweet
            try {
                java.lang.reflect.Method mD = tweetObj.getClass().getDeclaredMethod("D");
                mD.setAccessible(true);
                Object resD = mD.invoke(tweetObj);
                if (resD instanceof String && !((String) resD).isEmpty()) {
                    String dStr = (String) resD;
                    if (!usernames.contains(dStr)) {
                        usernames.add(dStr);
                    }
                }
            } catch (Exception ignored) {}

            return usernames;
        } catch (Exception e) {
            Logger.printException(() -> "TimelineMuteFilter.getRetweeterUsernames error", e);
            return null;
        }
    }

    public static boolean shouldMute(Object tweetObj) {
        if (!Pref.enableTimelineMuteFilter() || !SettingsStatus.timelineMuteFilter) {
            return false;
        }
        if (tweetObj == null) {
            return false;
        }

        try {
            updateCacheIfNeeded();

            if (cachedWordsList.isEmpty() && cachedWordsPatterns.isEmpty() && cachedUsersList.isEmpty()) {
                return false;
            }

            Tweet tweet = new Tweet(tweetObj);
            String username = null;
            try {
                username = tweet.getTweetUsername();
            } catch (Exception ignored) {}

            if (username == null || username.isEmpty()) {
                try {
                    java.lang.reflect.Method m = tweetObj.getClass().getDeclaredMethod("w");
                    m.setAccessible(true);
                    Object res = m.invoke(tweetObj);
                    if (res instanceof String) username = (String) res;
                } catch (Exception ignored) {}
            }
            if (username == null || username.isEmpty()) {
                try {
                    java.lang.reflect.Method m = tweetObj.getClass().getDeclaredMethod("D");
                    m.setAccessible(true);
                    Object res = m.invoke(tweetObj);
                    if (res instanceof String) username = (String) res;
                } catch (Exception ignored) {}
            }

            String profileName = null;
            try {
                profileName = tweet.getTweetProfileName();
            } catch (Exception ignored) {}

            String text = null;
            try {
                text = tweet.getText();
            } catch (Exception ignored) {}

            // 1. ユーザー名チェック (@screen_name)
            if (!cachedUsersList.isEmpty()) {
                if (username != null && !username.isEmpty()) {
                    String lowerUser = username.toLowerCase();
                    for (String mutedUser : cachedUsersList) {
                        if (lowerUser.equals(mutedUser)) {
                            android.util.Log.d("PikoMute", "Muted by author username: " + lowerUser);
                            return true;
                        }
                    }
                }

                // リポスト元（リポストしたユーザー）のチェック
                if (Pref.timelineMuteRetweets()) {
                    List<String> retweeterUsernames = getRetweeterUsernames(tweetObj);
                    if (retweeterUsernames != null) {
                        for (String retweeter : retweeterUsernames) {
                            if (retweeter != null && !retweeter.isEmpty()) {
                                String cleanRetweeter = retweeter.replace("@", "").toLowerCase();
                                for (String mutedUser : cachedUsersList) {
                                    if (cleanRetweeter.equals(mutedUser)) {
                                        android.util.Log.d("PikoMute", "Muted by retweeter: " + cleanRetweeter);
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. ワードチェック (通常部分一致)
            if (!cachedWordsList.isEmpty()) {
                String lowerText = text != null ? text.toLowerCase() : "";
                String lowerProfile = profileName != null ? profileName.toLowerCase() : "";
                for (String word : cachedWordsList) {
                    if ((!lowerText.isEmpty() && lowerText.contains(word)) ||
                        (!lowerProfile.isEmpty() && lowerProfile.contains(word))) {
                        android.util.Log.d("PikoMute", "Muted by keyword: " + word);
                        return true;
                    }
                }
            }

            // 3. ワードチェック (正規表現)
            if (!cachedWordsPatterns.isEmpty()) {
                String targetText = text != null ? text : "";
                String targetProfile = profileName != null ? profileName : "";
                for (Pattern pattern : cachedWordsPatterns) {
                    if ((!targetText.isEmpty() && pattern.matcher(targetText).find()) ||
                        (!targetProfile.isEmpty() && pattern.matcher(targetProfile).find())) {
                        android.util.Log.d("PikoMute", "Muted by regex: " + pattern.pattern());
                        return true;
                    }
                }
            }

        } catch (Exception e) {
            Logger.printException(() -> "TimelineMuteFilter.shouldMute error", e);
        }

        return false;
    }

    /**
     * InlineActionBar から祖先を探索し、RecyclerView アイテムのセルルート View を取得する
     */
    private static View findTweetRootView(View inlineActionBar) {
        View current = inlineActionBar;
        while (current.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) current.getParent();
            String parentClassName = parent.getClass().getName();
            if (parentClassName.contains("RecyclerView") || parentClassName.contains("ListView")) {
                return current;
            }
            current = parent;
        }
        return current;
    }

    public static void filterTweet(View inlineActionBar, Object tweetObj) {
        if (inlineActionBar == null) return;

        try {
            boolean mute = shouldMute(tweetObj);
            View tweetRoot = findTweetRootView(inlineActionBar);
            if (tweetRoot == null) return;

            ViewGroup.LayoutParams lp = tweetRoot.getLayoutParams();
            if (mute) {
                tweetRoot.setVisibility(View.GONE);
                inlineActionBar.setVisibility(View.GONE);
                if (lp != null && lp.height != 0) {
                    lp.height = 0;
                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                        mlp.topMargin = 0;
                        mlp.bottomMargin = 0;
                    }
                    tweetRoot.setLayoutParams(lp);
                }
            } else {
                if (tweetRoot.getVisibility() == View.GONE) {
                    tweetRoot.setVisibility(View.VISIBLE);
                    if (lp != null && lp.height == 0) {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        tweetRoot.setLayoutParams(lp);
                    }
                }
                if (inlineActionBar.getVisibility() == View.GONE) {
                    inlineActionBar.setVisibility(View.VISIBLE);
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "TimelineMuteFilter.filterTweet error", e);
        }
    }
}
