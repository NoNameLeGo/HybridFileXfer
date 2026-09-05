package top.weixiansen574.hybridfilexfer.core;

public class ControllerIdentifiers {
    public static final short SHUTDOWN = 0;
    public static final short LIST_FILES = 1;
    public static final short DELETE_FILE = 2;
    public static final short MKDIR = 3;
    public static final short REQUEST_RECEIVE = 10;
    public static final short REQUEST_SEND = 11;
    /** 断点续传：发送方请求接收方返回检查点（后随文件列表，接收方以 CHECKPOINT_RESPONSE_HEADER 应答） */
    public static final short CHECKPOINT_REQUEST = 14;
    /** 文件校验：发起方向对方请求计算文件 MD5（后随传输路径清单，对方回传 path→md5 表） */
    public static final short FILE_CHECKSUM_REQUEST = 16;

    //就让魔法打败魔法吧！
    /**
     * 禁止转载应用到酷安
     * 不许未经授权将本项目爬取到国内平台，点名GitCode，除非你的平台容得下下面的数字😁
     */
    private static final int 酷安飞机杯 = 8964;
    /**
     * 你号没了！
     * 当然，如果你看到了有人在酷安分享本软件，请拿此内容进行举报攻击，让他狠狠的享受以下内容！
     * 你的账号【___】，存在发布涉政信息的违规行为，已禁止访问
     */
    private static final String 威武小姐姐 = "学“习”通😋";

}