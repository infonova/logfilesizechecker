package hudson.plugins.logfilesizechecker;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.triggers.SafeTimerTask;
import hudson.triggers.Trigger;
import hudson.util.FormValidation;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;

/**
 * {@link BuildWrapper} that terminates a build if its log file size is too big.
 *
 * @author Stefan Brausch
 */
public class LogfilesizecheckerWrapper extends BuildWrapper {

    public static class MaxLogFileSizeReached extends CauseOfInterruption {

        private final long logFileSize;

        MaxLogFileSizeReached(long logFileSize) {
            this.logFileSize = logFileSize;
        }

        @Override
        public String getShortDescription() {
            return "Aborting the build because max log file size was reached (size: " + logFileSize + ")";
        }
    }
    
    /** Set your own max size instaed of using the default.*/
    public boolean setOwn;

    /** If the log file for the build has more MB, it will be terminated. */
    public int maxLogSize;

    /** Fail the build rather than aborting it. */
    public boolean failBuild;
    
    /**Period for timer task that checks the logfile size.*/
    private static final long PERIOD = 1000L;

    /**Delay for timer task that checks the logfile size.*/
    private static final long DELAY = 1000L;

    /**Conversion factor for Mega Bytes.*/
    private static final long MB = 1024L * 1024L;
    
    /**
     * Contructor for data binding of form data.
     * @param maxLogSize job specific maximum log size
     * @param failBuild true if the build should be marked failed instead of aborted
     * @param setOwn true if a job specific log size is set, false if global setting is used
     */
    @DataBoundConstructor
    public LogfilesizecheckerWrapper(int maxLogSize, boolean failBuild, boolean setOwn) {
        this.maxLogSize = maxLogSize;
        this.failBuild = failBuild;
        this.setOwn = setOwn;
    }
    
    @Override
    public Environment setUp(final AbstractBuild build, Launcher launcher,
            final BuildListener listener) throws IOException {
        
        /**Environment of the BuildWrapper.*/
        class EnvironmentImpl extends Environment {
            private final LogSizeTimerTask logtask;
            private final int allowedLogSize;

            /**
             * Constructor for Environment of BuildWrapper
             * Finds correct maximum log size and starts timertask
             */
            public EnvironmentImpl() {
                if (setOwn) {
                    allowedLogSize = maxLogSize;
                } else {
                    allowedLogSize = getDescriptorImpl().getGlobalMaxLogSize();
                }

                logtask = new LogSizeTimerTask(build, listener);
                if (allowedLogSize > 0) {
                    Trigger.timer.scheduleAtFixedRate(logtask, DELAY, PERIOD);
                }
            }

            /**TimerTask that checks log file size in regular intervals.*/
            final class LogSizeTimerTask extends SafeTimerTask {
                private final AbstractBuild build;
                private final BuildListener listener;

                /**
                 * Constructor for TimerTask that checks log file size.
                 * @param build the current build
                 * @param listener BuildListener used for logging
                 */
                private LogSizeTimerTask(AbstractBuild build, BuildListener listener) {
                    this.build = build;
                    this.listener = listener;
                }
                
                /**Interrupts build if log file is too big.*/
                public void doRun() {
                    final Executor e = build.getExecutor();
                    if (e != null 
                            && build.getLogFile().length() > allowedLogSize * MB 
                            && !e.isInterrupted()) {

                        CauseOfInterruption causeOfInterruption = new MaxLogFileSizeReached(build.getLogFile().length());
                        listener.getLogger().println(causeOfInterruption.getShortDescription());
                        e.interrupt(failBuild ? Result.FAILURE : Result.ABORTED, causeOfInterruption);
                    }
                }
            }

            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener)
                throws IOException, InterruptedException {
                if (allowedLogSize > 0) {
                    logtask.cancel();
                }
                return true;
            }
        }
        
        listener.getLogger().println(
                "Executor: " + build.getExecutor().getNumber());
        return new EnvironmentImpl();
    }

    @Override
    public Descriptor<BuildWrapper> getDescriptor() {
        return Jenkins.getInstance().getDescriptorOrDie(getClass());
    }

    private DescriptorImpl getDescriptorImpl() {
        return ((DescriptorImpl)getDescriptor());
    }

    /**The Descriptor for the BuildWrapper.*/
    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        public static final int DEFAULT_GLOBAL_MAX_LOG_SIZE = 0;

        /**If there is no job specific size set, this will be used.*/
        private int globalMaxLogSize;

        /**Constructor loads previously saved form data.*/
        public DescriptorImpl() {
            super(LogfilesizecheckerWrapper.class);
            load();
        }

        /**
         * Returns caption for our part of the config page.
         * @return caption
         */
        public String getDisplayName() {
            return Messages.Descriptor_DisplayName();
        }

        /**Certainly does something.
         * @param item Some item, I guess
         * @return true
         */
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        /**
         * Returns maximum log size set in global configuration.
         * @return the global max log size
         */
        public int getGlobalMaxLogSize() {
            return globalMaxLogSize;
        }

        /**
         * Allows changing the global log file size - used for testing only.
         * @param globalMaxLogSize new global max log size
         */
        public void setGlobalMaxLogSize(int globalMaxLogSize) {
            this.globalMaxLogSize = globalMaxLogSize;
        }

        @Override
        @RequirePOST
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            globalMaxLogSize = checkGlobalMaxLogSize(formData.getInt("globalMaxLogSize"));

            save();
            return super.configure(req, formData);
        }

        @Override
        public BuildWrapper newInstance(StaplerRequest req, JSONObject formData)
            throws FormException {
            
            final JSONObject newData = new JSONObject();
            newData.put("failBuild", formData.getString("failBuild"));
            
            final JSONObject sizeObject = formData.getJSONObject("logfilesizechecker");
            if ("setOwn".equals(sizeObject.getString("value"))) {
                newData.put("setOwn", true);
                newData.put("maxLogSize", sizeObject.getString("maxLogSize"));
            } else {
                newData.put("setOwn", false);
            }
            
            return super.newInstance(req, newData);
        }

        private Integer checkGlobalMaxLogSize(Integer globalMaxLogSize) throws Failure {
            if (globalMaxLogSize == null) {
                throw new Failure("Please specify a max log file size");
            } else if (globalMaxLogSize < 0) {
                throw new Failure("Please specify a positive value for max log file size");
            }

            return globalMaxLogSize;
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckGlobalMaxLogSize(@QueryParameter Integer globalMaxLogSize) {
            try {
                checkGlobalMaxLogSize(globalMaxLogSize);
                return FormValidation.ok();
            } catch (Failure e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
