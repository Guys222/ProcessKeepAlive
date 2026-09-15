package io.github.guys222.processkeepalive;

/** 单个目标应用的保活配置。 */
public class AppConfig {
    /** 保活档位：0=前台级 1=可见级 2=可感知级。 */
    public int adj = 1;
    public boolean forceStop = true;
    public boolean killBackground = true;
    public boolean kill = false;
    public boolean persistent = false;
    /** 消息保活（后台收消息：Doze/Standby 豁免 + 主进程伪装前台）。 */
    public boolean msg = false;
}
