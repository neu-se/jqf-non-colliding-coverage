package janala.instrument;

/** An object to keep track of (instructionId) during
 instrumentation. */
public class GlobalStateForInstrumentation {
  public static GlobalStateForInstrumentation instance = new GlobalStateForInstrumentation();
  private int iid = 0;

  public int incAndGetId() {
    iid++;
    return iid;
  }
}
