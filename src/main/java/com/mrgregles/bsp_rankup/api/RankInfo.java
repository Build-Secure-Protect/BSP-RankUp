package com.mrgregles.bsp_rankup.api;

/** One rung of the ladder as other mods see it: the LuckPerms group, the name shown, its place (0 = first) and the points it needs. */
public record RankInfo(String group, String displayName, int index, long points, boolean seasonal) {
}
