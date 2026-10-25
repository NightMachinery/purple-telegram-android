package android.database;
public final class MatrixCursor implements Cursor { public final String[] columns;public Object[] row;public MatrixCursor(String[] c,int n){columns=c;}public void addRow(Object[] r){row=r;}public void close(){} }
