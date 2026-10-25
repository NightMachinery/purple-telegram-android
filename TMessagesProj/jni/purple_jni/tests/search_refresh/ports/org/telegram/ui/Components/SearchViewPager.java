package org.telegram.ui.Components;
import org.telegram.ui.Adapters.DialogsSearchAdapter;
public final class SearchViewPager {
    public DialogsSearchAdapter dialogsSearchAdapter;
    private int visibility;
    public int getVisibility() { return visibility; }
    public void setVisibility(int value) { visibility = value; }
    public SearchViewPager(DialogsSearchAdapter adapter) { dialogsSearchAdapter = adapter; }
}
