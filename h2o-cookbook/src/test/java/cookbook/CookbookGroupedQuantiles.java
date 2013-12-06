package cookbook;

import org.junit.Before;
import org.junit.Test;
import water.*;
import water.exec.Flow;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.util.Log;
import water.util.RemoveAllKeysTask;
import water.util.Utils;
import water.util.Utils.IcedHashMap;
import water.util.Utils.IcedLong;

public class CookbookGroupedQuantiles extends  TestUtil {
  @Before
  public void removeAllKeys() {
    Log.info("Removing all keys...");
    RemoveAllKeysTask collector = new RemoveAllKeysTask();
    collector.invokeOnAllNodes();
    Log.info("Removed all keys.");
  }

  static class AddSparseGroupNumber extends MRTask2<AddSparseGroupNumber> {
    final int cyl_idx;
    final int sg_idx;
    public AddSparseGroupNumber(int cix, int gix) {cyl_idx = cix; sg_idx= gix;}
    @Override public void map(Chunk[] cs) {
      for (int i = 0; i < cs[0]._len; i++) {
        long cyl = cs[cyl_idx].at80(i);
        cs[sg_idx].set0(i,cyl);
      }
    }
  }

  static class CompactGroupNumber extends MRTask2<CompactGroupNumber> {
    final int sg_idx;
    IcedHashMap<IcedLong, IcedLong> sparse_group_number_set;
    public CompactGroupNumber(int gix) { sg_idx = gix; }
    @Override public void map(Chunk[] cs) {
      sparse_group_number_set = new IcedHashMap<IcedLong, IcedLong>();
      for (int i = 0; i < cs[0]._len; i++) {
        long sgid = cs[sg_idx].at80(i);
        IcedLong sgid_ice = new IcedLong(sgid);
        sparse_group_number_set.put(sgid_ice, null);
      }
    }
    @Override public void reduce( CompactGroupNumber that ) {
      this.sparse_group_number_set.putAll(that.sparse_group_number_set);
    }
  }

  static class AssignCompactGroupNumber extends MRTask2<AssignCompactGroupNumber> {
    final int sg_idx;
    final int dg_idx;
    final IcedHashMap<IcedLong, IcedLong> gid_map;
    public AssignCompactGroupNumber(IcedHashMap<IcedLong, IcedLong> gm, int sgi, int dgi ) {
      gid_map = gm;
      sg_idx  = sgi;
      dg_idx  = dgi;
    }
    @Override public void map(Chunk[] cs) {
      for (int i = 0; i < cs[0]._len; i++) {
        long sgid = cs[sg_idx].at80(i);
        IcedLong sgid_ice = new IcedLong(sgid);
        IcedLong dgid_ice = gid_map.get(sgid_ice);
        if (dgid_ice == null) throw new RuntimeException("GID missing.");
        cs[dg_idx].set0(i, dgid_ice._val);
      }
    }
  }

  static class MyGroupBy extends Flow.GroupBy {
    int dg_idx;
    public MyGroupBy(int gix) {dg_idx = gix;}
    public long groupId(double ds[]) {
      return (long) ds[dg_idx];
    }
  }

  static class BasicSummary extends Flow.PerRow<BasicSummary> {
    double _min;
    double _max;
    long _n;
    int  _val_idx;
    public BasicSummary(int vix) {
      _min = Double.MAX_VALUE;
      _max = Double.MIN_VALUE;
      _n = 0;
      _val_idx = vix;
    }
    @Override public void mapreduce( double ds[] ) {
      _min  = Math.min(_min, ds[_val_idx]);
      _max  = Math.max(_max, ds[_val_idx]);
      _n ++;
    }

    @Override public void reduce( BasicSummary that ) {
      _min = Math.min(_min, that._min);
      _max = Math.max(_max, that._max);
      _n += that._n;
    }

    @Override public BasicSummary make() {
      BasicSummary b = new BasicSummary(_val_idx);
      return b;
    }
  }

  @Test
  public void testGroupedQuantiles() {
    final String INPUT_FILE_NAME = "/Users/bai/testdata/year2013.csv";// "../smalldata/cars.csv";
    final String GROUP_COLUMN_NAME = "CRSDepTime"; // "cylinders";
    final String VALUE_COLUMN_NAME = "Distance"; // "cylinders";
    final String KEY_STRING = "year2013.hex";
    Key k = Key.make(KEY_STRING);
    Frame fr = parseFrame(k, INPUT_FILE_NAME);

    // Pass 0, add group number columns to frame
    fr.add("sparse_group_number", fr.anyVec().makeZero());
    fr.add("dense_group_number", fr.anyVec().makeZero());
    Futures fs = new Futures();
    UKV.put(k, fr, fs);
    fs.blockForPending();

    // Pass 1, assign group numbers to rows
    final int cyl_idx = fr.find(GROUP_COLUMN_NAME);
    final int sg_idx  = fr.find("sparse_group_number");
    final int dg_idx  = fr.find("dense_group_number");
    final int val_idx = fr.find(VALUE_COLUMN_NAME);
    new AddSparseGroupNumber(cyl_idx, sg_idx).doAll(fr);

    // Pass 2, compact group numbers
    IcedHashMap<IcedLong, IcedLong> sparse_group_number_set =
            new CompactGroupNumber(sg_idx).doAll(fr).sparse_group_number_set;

    int ng = 1;
    for (IcedLong key : sparse_group_number_set.keySet())
      sparse_group_number_set.put(key, new IcedLong(ng++));

    // Pass 3, assign dense group numbers
    new AssignCompactGroupNumber(sparse_group_number_set, sg_idx, dg_idx).doAll(fr);

    // Pass 4, collect basic stats for each dense group

    IcedHashMap<IcedLong, BasicSummary> basic_summaries =
      fr.with(new MyGroupBy(dg_idx))
      .with(new BasicSummary(val_idx))
      .doit();

     for (IcedLong key : basic_summaries.keySet()) {
       BasicSummary bs = basic_summaries.get(key);
       Log.info("sgid " + key._val + "  min " + bs._min + "  max " + bs._max, " n " + bs._n);
     }

    //Log.info("SIZE " + sparse_group_number_set.keySet().size());

    try { Thread.sleep(100000000); } catch (Exception e) {}

    UKV.remove(k);
  }
}
