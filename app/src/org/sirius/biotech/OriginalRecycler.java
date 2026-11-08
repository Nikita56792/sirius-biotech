package org.sirius.biotech;

import android.view.*;
import java.util.*;

/** Transport adapter only: rows and list widgets are the original MSS views. */
final class OriginalRecycler extends b8.x0 {
    interface Row { View create(ViewGroup parent) throws Exception; }
    private final List<Row> rows;
    OriginalRecycler(List<Row> rows){this.rows=new ArrayList<>(rows);}
    void append(Row row){int index=rows.size();rows.add(row);i(index,1);}
    public int c(){return rows.size();}
    public int e(int position){return position;}
    public void k(b8.y1 holder,int position){}
    public b8.y1 m(ViewGroup parent,int position){try{View row=rows.get(position).create(parent);if(row.getLayoutParams()==null)row.setLayoutParams(new ViewGroup.LayoutParams(-1,-2));return new Holder(row);}catch(Exception error){throw new IllegalStateException("MSS row binding failed",error);}}
    static final class Holder extends b8.y1 { Holder(View view){super(view);} }
}
