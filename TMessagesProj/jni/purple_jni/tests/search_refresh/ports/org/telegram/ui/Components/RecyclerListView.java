package org.telegram.ui.Components;
import androidx.recyclerview.widget.RecyclerView;
public final class RecyclerListView {
    private final RecyclerView.Adapter adapter;
    public RecyclerListView(RecyclerView.Adapter adapter) { this.adapter = adapter; }
    public RecyclerView.Adapter getAdapter() { return adapter; }
}
