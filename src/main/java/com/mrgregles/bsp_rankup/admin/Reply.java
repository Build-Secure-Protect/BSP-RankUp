package com.mrgregles.bsp_rankup.admin;

/** What an admin edit answers: whether it happened and one plain line for the admin. */
public record Reply(boolean ok, String text) {
    public static Reply ok(String text) {
        return new Reply(true, text);
    }

    public static Reply no(String text) {
        return new Reply(false, text);
    }
}
