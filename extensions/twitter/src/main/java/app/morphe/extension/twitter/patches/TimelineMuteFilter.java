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
            String username = tweet.getTweetUsername();
            String profileName = tweet.getTweetProfileName();
            String text = tweet.getText();

            // 1. ユーザー名チェック (@screen_name)
            if (username != null && !cachedUsersList.isEmpty()) {
                String lowerUser = username.toLowerCase();
                for (String mutedUser : cachedUsersList) {
                    if (lowerUser.equals(mutedUser)) {
                        return true;
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
            if (parentClassName.contains("TweetView")) {
                ViewParent grandParent = parent.getParent();
                if (grandParent instanceof ViewGroup) {
                    String grandParentName = grandParent.getClass().getName();
                    if (grandParentName.contains("RecyclerView") || grandParentName.contains("ListView")) {
                        return parent;
                    }
                }
                return parent;
            }
            current = parent;
        }
        return inlineActionBar;
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
                if (lp != null && lp.height != 0) {
                    lp.height = 0;
                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ((ViewGroup.MarginLayoutParams) lp).topMargin = 0;
                        ((ViewGroup.MarginLayoutParams) lp).bottomMargin = 0;
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
            }
        } catch (Exception e) {
            Logger.printException(() -> "TimelineMuteFilter.filterTweet error", e);
        }
    }
}
