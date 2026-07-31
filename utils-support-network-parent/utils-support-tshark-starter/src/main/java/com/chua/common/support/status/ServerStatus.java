package com.chua.common.support.status;

public enum ServerStatus {
    STARTING {
        @Override
        public boolean canAcceptRequests() {
            return false;
        }
    },
    RUNNING {
        @Override
        public boolean canAcceptRequests() {
            return true;
        }
    },
    STOPPING {
        @Override
        public boolean canAcceptRequests() {
            return false;
        }
    },
    STOPPED {
        @Override
        public boolean canAcceptRequests() {
            return false;
        }
    },
    PAUSED {
        @Override
        public boolean canAcceptRequests() {
            return false;
        }
    },
    ERROR {
        @Override
        public boolean canAcceptRequests() {
            return false;
        }
    };

    public abstract boolean canAcceptRequests();
}
