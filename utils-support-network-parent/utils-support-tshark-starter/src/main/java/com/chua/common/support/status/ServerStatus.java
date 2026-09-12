package com.chua.common.support.status;

/**
   * 服务器运行状态枚举，标记 启动 / RUNNING / STOPPING / STOPPED / PAUSED / 错误 六种生命周期阶段。
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum ServerStatus {

    /**
     * 启动中：尚未接受请求
     */
    STARTING {
        @Override
        /** 是否可以acceptRequests */
        public boolean canAcceptRequests() {
            return false;
        }
    },

    /**
     * 运行中：接受请求
     */
    RUNNING {
        @Override
        /** 是否可以acceptRequests */
        public boolean canAcceptRequests() {
            return true;
        }
    },

    /**
     * 停止中：暂不接受新请求，等待正在处理的请求完成
     */
    STOPPING {
        @Override
        /** 是否可以acceptRequests */
        public boolean canAcceptRequests() {
            return false;
        }
    },

    /**
     * 已停止：不接受请求
     */
    STOPPED {
        @Override
        /** 是否可以acceptRequests */
        public boolean canAcceptRequests() {
            return false;
        }
    },

    /**
     * 已暂停：临时不接受请求，可恢复
     */
    PAUSED {
        @Override
        /** 是否可以acceptRequests */
        public boolean canAcceptRequests() {
            return false;
        }
    },

    /**
     * 异常状态：发生错误，拒绝请求
     */
    ERROR {
        @Override
        /** 是否可以acceptRequests */
        public boolean canAcceptRequests() {
            return false;
        }
    };

    /**
     * 当前状态下服务器是否接受请求。
     *
     * @return true 表示可接受请求
     */
    public abstract boolean canAcceptRequests();
}
