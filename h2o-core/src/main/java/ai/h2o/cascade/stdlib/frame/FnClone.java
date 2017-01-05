package ai.h2o.cascade.stdlib.frame;

import ai.h2o.cascade.core.CorporealFrame;
import ai.h2o.cascade.core.GhostFrame;
import ai.h2o.cascade.stdlib.StdlibFunction;
import water.DKV;
import water.Key;
import water.MRTask;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;

/**
 * Make a deep copy of the frame.
 */
public class FnClone extends StdlibFunction {

  public GhostFrame apply(GhostFrame frame) {
    if (frame instanceof CorporealFrame) {
      Frame srcFrame = ((CorporealFrame) frame).getWrappedFrame();
      Key<Frame> destKey = scope.session().mintKey();
      Frame destFrame = cloneFrame(srcFrame, destKey);
      return new CorporealFrame(destFrame);
    } else {
      // If the source frame is not corporeal, then materializing it is
      // equivalent to creating a copy.
      return frame.materialize(scope);
    }
  }


  /**
   * Return deep copy of the given frame. Here "deep" means that all vecs are
   * actually copied (as opposed to a shallow copy of the frame, where vecs
   * are reused from the original frame).
   *
   * @see Frame#deepCopy(String)
   * @see Vec#doCopy()
   */
  public static Frame cloneFrame(Frame frame, Key<Frame> newKey) {
    Vec somevec = frame.anyVec();
    if (somevec == null) {
      return new Frame();
    }
    int n = frame.numCols();
    Key<Vec>[] keys = somevec.group().addVecs(n);
    Vec[] vecs = new Vec[n];
    for (int i = 0; i < n; i++) {
      Vec vi = frame.vec(i);
      vecs[i] = new Vec(keys[i], somevec._rowLayout, vi.domain(), vi.get_type());
    }
    new DeepCopyTask(vecs).doAll(frame);
    return new Frame(newKey, frame.names().clone(), vecs);
  }


  /** MRTask that does the actual cloning job.*/
  private static class DeepCopyTask extends MRTask<DeepCopyTask> {
    private Vec[] vecs;

    public DeepCopyTask(Vec[] vecs) {
      this.vecs = vecs;
    }

    @Override public void map(Chunk[] chunks){
      for (int i = 0; i < vecs.length; i++) {
        Chunk newchunk = chunks[i].deepCopy();
        DKV.put(vecs[i].chunkKey(chunks[i].cidx()), newchunk, _fs);
      }
    }

    @Override public void postGlobal() {
      for (Vec v: vecs) {
        DKV.put(v, _fs);
      }
      _fs.blockForPending();
    }
  }
}
