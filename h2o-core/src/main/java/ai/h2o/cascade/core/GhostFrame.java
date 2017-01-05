package ai.h2o.cascade.core;

import water.MRTask;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.NewChunk;
import water.fvec.Vec;
import water.parser.BufferedString;
import water.util.ArrayUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * WIP
 *
 * This should be the base class for all "frames" produced in Cascade.
 */
public abstract class GhostFrame extends Val {

  /** Number of columns in the frame. */
  public abstract int numCols();

  /** Number of rows in the frame. */
  public abstract long numRows();

  /** Type of the {@code i}-th column.
   * (Should this be a {@code Type} enum instead?) */
  public abstract byte type(int i);

  /** Name of the {@code i}-th column. */
  public abstract String name(int i);


  /**
   * <p> If the {@code GhostFrame} depends on 1 or more external {@link Vec}s,
   * then it should override this method and append herein all the dependents
   * onto the list of {@code inputs}. Thus, this method mutates the
   * provided argument.
   *
   * <p> If the {@code GhostFrame} depends on 1 or more other {@code GhostFrame}s, it
   * should call their {@code prepareInput()} methods recursively.
   *
   * <p> If you put some {@code Vec}s onto the list of inputs, then expect
   * those {@code Vec}s to appear in the array of {@link Chunk}s during the
   * row-by-row computation at the same position as you put them into the
   * list of {@code inputs}.
   */
  protected abstract void prepareInputs(List<Vec> inputs);


  /**
   * When an {@link MRTask} is run, this method will be invoked once per each
   * invocation of {@link MRTask#map(Chunk[])}.
   *
   * <p> If your {@code GhostFrame} requires some local initialization (for example
   * to initialize a reusable output array, or a random number generator
   * object, etc), or if it needs to read inputs from external {@code Vec}s,
   * then you can do so in this method.
   *
   * <p> If your {@code GhostFrame} depends on 1 or more other {@code GhostFrame}s, you
   * should call their {@code preparePerChunk()} methods recursively.
   */
  protected abstract void preparePerChunk(Chunk[] cs);


  /**
   * Compute and return the (numeric) value for the given {@code row} and
   * {@code col}. Here {@code row} is the index within the current chunk, and
   * {@code col} spans the range from 0 to {@code numCols() - 1}. This method
   * will always be called in the order of increasing rows, and implementations
   * are free to compute their result once per row, then cache it and return
   * finally return each cell individually from this method. This method will
   * only be called for columns that produce numeric results.
   */
  public double getNumValue(int row, int col) { return Double.NaN; }

  public BufferedString getStrValue(int row, int col) { return null; }


  /**
   * Convert {@code GhostFrame} into a {@link CorporealFrame}: this method will
   * compute all values within the current frame, producing a "material"
   * {@link Frame}, wrapped into the {@code CorporealFrame} class.
   *
   * @param scope Current execution scope (needed to properly track the newly
   *              created {@code Frame}).
   */
  public CorporealFrame materialize(Scope scope) {
    ArrayList<Vec> inputs = new ArrayList<>();
    prepareInputs(inputs);
    Vec[] inputVecs = inputs.toArray(new Vec[inputs.size()]);

    // TODO: we will probably be better-off creating a SliceList instead
    final int nOutputs = numCols();
    byte[] outputTypes = new byte[nOutputs];
    String[] outputNames = new String[nOutputs];
    ArrayList<Integer> numericOutputs = new ArrayList<>();
    ArrayList<Integer> stringOutputs = new ArrayList<>();
    for (int i = 0; i < nOutputs; i++) {
      outputTypes[i] = type(i);
      outputNames[i] = name(i);
      // Q: what about domains -- esp. if they can't be known in advance?
      if (outputTypes[i] == Vec.T_STR || outputTypes[i] == Vec.T_UUID) {
        stringOutputs.add(i);
      } else {
        numericOutputs.add(i);
      }
    }

    MaterializationTask mtask = new MaterializationTask(numericOutputs, stringOutputs);
    Frame res = mtask.doAll(outputTypes, inputVecs)
                     .outputFrame(scope.<Frame>mintKey(), outputNames, null);

    return new CorporealFrame(res);
  }


  private class MaterializationTask extends MRTask<MaterializationTask> {
    private int[] numericOutputIndices;
    private int[] stringOutputIndices;

    public MaterializationTask(ArrayList<Integer> numericOutputs, ArrayList<Integer> stringOutputs) {
      numericOutputIndices = ArrayUtils.toIntArray(numericOutputs);
      stringOutputIndices = ArrayUtils.toIntArray(stringOutputs);
    }

    @Override public void map(Chunk[] cs, NewChunk[] ncs) {
      preparePerChunk(cs);
      int nRowsInChunk = cs[0]._len;
      for (int i = 0; i < nRowsInChunk; i++) {
        for (int j: numericOutputIndices) {
          ncs[j].addNum(getNumValue(i, j));
        }
        for (int j: stringOutputIndices) {
          ncs[j].addStr(getStrValue(i, j));
        }
      }
    }

  }



  //--------------------------------------------------------------------------------------------------------------------
  // Val interface
  //--------------------------------------------------------------------------------------------------------------------

  @Override
  public Val.Type type() {
    return Val.Type.FRAME;
  }

  @Override
  public boolean isFrame() {
    return true;
  }

  @Override
  public GhostFrame getFrame() {
    return this;
  }


  //--------------------------------------------------------------------------------------------------------------------
  // Frame operations
  //--------------------------------------------------------------------------------------------------------------------

  /**
   * Return index of the column with the given {@code name}, or {@code -1} if
   * such column does not exist in the frame.
   *
   * <p>Note: this performs linear O(N) search, and is therefore not very
   * optimal for bulk search of multiple column names.
   */
  public int findColumnByName(String name) {
    int ncols = numCols();
    for (int i = 0; i < ncols; i++) {
      if (name(i).equals(name)) {
        return i;
      }
    }
    return -1;
  }

}
