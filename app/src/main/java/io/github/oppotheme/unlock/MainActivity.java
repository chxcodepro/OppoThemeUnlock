package io.github.oppotheme.unlock;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 模块状态页。
 *
 * LSPosed 只按 xposedmodule 元数据识别模块，本页不参与 hook，
 * 存在的意义是让用户在桌面上有个入口确认：目标包在不在、版本对不对、
 * 该把作用域勾成什么。界面用代码构建，不依赖 layout 资源。
 */
public class MainActivity extends Activity {

    private static final String TARGET = UnlockConfig.TARGET_PACKAGE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());
    }

    private ScrollView build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0E1116"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(title("主题商店 解锁"));
        root.addView(sub("LSPosed 模块 · v" + HookEntry.BuildConfigCompat.VERSION_NAME));
        root.addView(gap(20));

        root.addView(section("作用域"));
        root.addView(body("启用模块后，作用域请只勾选：\n" + TARGET
                + "\n\n商店是多进程应用，:engine / :bathmos / :pet 会由模块自动一并覆盖。"));
        root.addView(gap(16));

        root.addView(section("目标应用"));
        root.addView(body(describeTarget()));
        root.addView(gap(16));

        root.addView(section("解锁范围"));
        root.addView(body(
                "会员身份\n"
                        + "  · VipUserDto 字段与取值\n"
                        + "  · UserInfoManager / BizManager / interfaceLayer\n"
                        + "    全部返回 VipUserStatus 的方法固定为 VALID\n\n"
                        + "资源权限码（核心）\n"
                        + "  · ResTypeUtil.getResTypeWithVipStatus 全部重载\n"
                        + "  · ResTypeVipUtil.getResTypeWithVipStatus\n"
                        + "  · ResourceUtil.isFree 兜底放行\n"
                        + "  · 覆盖主题 / 壁纸 / 字体 / 息屏 / 小组件\n\n"
                        + "下载信息（第二道闸门）\n"
                        + "  · DownloadResponseItemDto.getStatus 拒绝码归零\n"
                        + "  · 解决「获取下载信息失败，原因：未购买」\n\n"
                        + "试用与广告\n"
                        + "  · 阻断 *_EXPIRE 到期回收广播\n"
                        + "  · 开屏广告时长压至最小"));
        root.addView(gap(16));

        root.addView(section("启用步骤"));
        root.addView(body("1. 在 LSPosed 中启用本模块\n"
                + "2. 勾选上方作用域\n"
                + "3. 强制停止主题商店后重新打开\n"
                + "4. 查看 LSPosed 日志中 [ThemeUnlock] 开头的内容"));
        root.addView(gap(16));

        root.addView(section("日志过滤"));
        root.addView(body("logcat | grep ThemeUnlock"));

        return scroll;
    }

    private String describeTarget() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(TARGET, 0);
            return "包名：" + TARGET
                    + "\n状态：已安装"
                    + "\n版本：" + info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable t) {
            return "包名：" + TARGET + "\n状态：未检测到该应用";
        }
    }

    /* ------------------------------------------------------------------ */

    private TextView title(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#F2F5F8"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        return tv;
    }

    private TextView sub(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#7C8B9C"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return tv;
    }

    private TextView section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#4DB6AC"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return tv;
    }

    private TextView body(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#C3CBD5"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(dp(4), 1f);
        return tv;
    }

    private LinearLayout gap(int height) {
        LinearLayout v = new LinearLayout(this);
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)));
        return v;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
