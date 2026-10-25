package org.telegram.ui.ActionBar;
public final class BaseFragment {
    private final int account;
    public BaseFragment(int account) { this.account = account; }
    public int getCurrentAccount() { return account; }
}
