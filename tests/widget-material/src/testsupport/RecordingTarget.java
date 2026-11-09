package testsupport;

import java.util.ArrayList;
import java.util.List;

/** One ordered trace across owned View and Paint objects; this is not a renderer. */
public abstract class RecordingTarget {
    public enum FailureMode { THROW_BEFORE_WRITE, THROW_AFTER_WRITE, RETURN_FALSE, RETURN_FALSE_WITHOUT_WRITE }

    public static final class InjectedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InjectedFailure(String target, String method, FailureMode mode) {
            super("Injected " + mode + " at " + target + "." + method);
        }
    }

    public static final class Call {
        public final RecordingTarget target;
        public final String method;
        public final boolean hiddenApi;
        /** Value snapshots and raw identities are both needed for recipe-cloning tests. */
        public final Object[] arguments;
        public final Object[] references;
        /** Mock method return value; null when the call threw before returning. */
        public Boolean returned;

        Call(RecordingTarget target, String method, boolean hiddenApi, Object[] args) {
            this.target = target;
            this.method = method;
            this.hiddenApi = hiddenApi;
            this.references = args.clone();
            this.arguments = args.clone();
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i] instanceof float[]) arguments[i] = ((float[]) arguments[i]).clone();
            }
        }

        @Override public String toString() {
            return target.name + "." + method + java.util.Arrays.deepToString(arguments);
        }
    }

    private static final class Failure {
        final String method;
        final FailureMode mode;
        final InjectedFailure exception;

        Failure(String target, String method, FailureMode mode) {
            this.method = method;
            this.mode = mode;
            this.exception = new InjectedFailure(target, method, mode);
        }
    }

    public static final List<Call> ALL_CALLS = new ArrayList<>();
    public static final List<RecordingTarget> ALL_TARGETS = new ArrayList<>();
    public final String name;
    public final List<Call> calls = new ArrayList<>();
    private final List<Failure> failures = new ArrayList<>();

    protected RecordingTarget(String name) { this.name = name; }

    public static void resetRecords() {
        ALL_CALLS.clear();
        ALL_TARGETS.clear();
    }

    public InjectedFailure failNext(String method, FailureMode mode) {
        Failure failure = new Failure(name, method, mode);
        failures.add(failure);
        return failure.exception;
    }

    protected boolean nativeCall(String method, Object... args) { return record(method, true, args); }
    protected void ordinaryCall(String method, Object... args) { record(method, false, args); }

    private boolean record(String method, boolean hiddenApi, Object[] args) {
        Call call = new Call(this, method, hiddenApi, args);
        calls.add(call);
        ALL_CALLS.add(call);
        Failure failure = null;
        for (int i = 0; i < failures.size(); i++) {
            if (failures.get(i).method.equals(method)) {
                failure = failures.remove(i);
                break;
            }
        }
        if (failure != null && failure.mode == FailureMode.THROW_BEFORE_WRITE) throw failure.exception;
        if (failure != null && failure.mode == FailureMode.RETURN_FALSE_WITHOUT_WRITE) {
            call.returned = false;
            return false;
        }
        boolean normalResult = resultBeforeWrite(method, args);
        applyState(method, args);
        if (failure != null && failure.mode == FailureMode.THROW_AFTER_WRITE) throw failure.exception;
        // Preserve API-specific results: pass-window reports false for an unchanged state.
        call.returned = failure != null && failure.mode == FailureMode.RETURN_FALSE ? false : normalResult;
        return call.returned;
    }

    protected boolean resultBeforeWrite(String method, Object[] args) { return true; }
    protected abstract void applyState(String method, Object[] args);
    @Override public String toString() { return name; }
}
