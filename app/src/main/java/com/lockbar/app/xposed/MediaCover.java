package com.lockbar.app.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 音乐封面背景 —— 玻璃取景的第二来源（round coverglass1 → frostglass3）。
 *
 * <p>起因（实测三连）：装了 HyperMusicCover（下称 HMC）放音乐时，它<b>分页面</b>改
 * 锁屏背景，而壁纸<b>文件</b>从头到尾没动过 —— 顶替发生在 {@code com.miui.miwallpaper}
 * 进程的 GL 纹理里（保住景深抠图），{@code WallpaperManager} 解出来的永远是原图。
 * 两页的屏上背景分别是：
 * <ul>
 *   <li><b>封面页</b>：壁纸原图 + 一张浮在上面的专辑卡片；</li>
 *   <li><b>歌词页</b>：它推给壁纸进程的<b>磨砂封面</b>（op lyricblur）—— 糊到只剩
 *       颜色的封面 + 两道压暗。</li>
 * </ul>
 *
 * <p><b>三轮实测的定案</b>：
 * <ul>
 *   <li><b>coverglass1</b>（拿「树里有 HMC View」当页面信号）：它的卡片歌词页也不摘，
 *       门槛两页都过、纹理只组一份还按 card 配方（48px + 8% 纱）→ 封面页串味、
 *       歌词页不同调；</li>
 *   <li><b>frostglass1/2</b>（歌词页判据换两条并联：歌词视图在树 / 卡片让位 + 会话活）
 *       实测<b>两轮都失败</b> —— 用户侧 HDR 歌词高光开，歌词走独立子窗树里探不到；
 *       卡片的挂摘时机又随手势与 HMC 版本变（上滑中途整层被摘，景深扫描日志实证）。
 *       页面级判据在这台机器上就是不可靠，不再拿它切纹理。</li>
 * </ul>
 *
 * <p><b>frostglass3 的拍板（不判页面）</b>：HMC 在场 + 会话活着 + 磨砂已备好 ⇒ 玻璃
 * 采磨砂，否则采壁纸文件。歌词页两者本就是同一张图（完全一致）；页面位降级为
 * {@link #hmcDump} 诊断日志，扫描结果一变就打一行，下次排查有据可依。
 *
 * <p><b>frostglass4（本轮，歌词页已验 → 封面页补全）</b>：frostglass3 装机后用户
 * 实测「歌词页取景正常了、封面页仍不对」，随后源码更新暴露了两个真相 ——
 * <ul>
 *   <li><b>装机的 HMC 是 release 构建，R8 把类全混淆了</b>（树里唯一可见的它的
 *       视图叫 {@code com.os4.musiccover.x}）：按类名认页面的判据在混淆构建上
 *       <b>永远不成立</b>，frostglass1/2 三轮全败的根子就在这里；而且上滑中途
 *       它会把视图摘出树（实测日志 found 1→0），树里看到的都是瞬时状态；</li>
 *   <li><b>两页的屏上背景不是一张图</b>：歌词页 = 它推的磨砂（frostglass3 已验
 *       正确）；封面页 = 它源码 {@code CoverCompose.composeWallpaper} 的合成图
 *       （镜像糊底 + 0x14 一道纱 + 按 bias 摆位的<b>羽化锐利横带</b>）—— 磨砂比
 *       它多压一道 0x61 又没有横带，颜色深了一档，这就是「仍不对」。</li>
 * </ul>
 * 拍板：
 * <ul>
 *   <li><b>页面改问它本人</b>：走 HMC 的<b>公开 op 协议</b>（{@link #queryPage}，
 *       有序广播 lyricstate 只读诊断项，adb 同一条链路）—— 应答是它状态机的
 *       答案，与混淆无关、与中途摘树无关；</li>
 *   <li><b>纹理按页分两份</b>：歌词页 {@link #frostedFrom}（配方一字不动）、
 *       封面页 {@link #coverFrom}（composeWallpaper 同式），一次 {@link #compose}
 *       共用取图各组一份；</li>
 *   <li><b>手势中途禁降级</b>（调用方 LockGlassStrip 的 latch）：上滑一半它摘
 *       视图是它对解锁的反应，不是音乐没了 —— 中途降门槛会掉回壁纸文件（封面页
 *       实测的冰纹就是这条），起手沿那次扫描才是权威升降。</li>
 * </ul>
 *
 * <p><b>frostglass5（本轮，实测「还是壁纸」的病根）</b>：frostglass4 装机后实测
 * 时间线 —— 应答通了（46.965 页面=歌词）、双份纹理就绪，但 51.289 后锁屏树里
 * 再找不到它的 View（重锁/切页它不回来），后续手势的「权威扫描」found=0 一降级，
 * 玻璃掉回壁纸文件，且同一条件把 queryPage 一起拦死（页面/封面模式冻结在旧值）。
 * <b>树摘 ≠ 背景回</b> —— 专辑背景顶替在壁纸进程里，它的 View 挂摘与背景无关，
 * 拿树当在场从来就是错的。定案：
 * <ul>
 *   <li><b>在场 = 应答</b>（{@link #hmcAlive}：应答到过 / 树见过，一次性 —— 两者
 *       都只能随 SystemUI 重启消失，静态也一起清零，无需超时）；树探到只作旁证；</li>
 *   <li><b>查询无条件</b>：装上 / 起手沿 / 换曲一律问，不再被树探到与否拦；</li>
 *   <li><b>采样换源打日志</b>（LockGlassStrip.applyTexture：谁→谁 + 四个门槛位），
 *       页面变化日志附应答原文片段 —— 解析对不对一行定案。</li>
 * </ul>
 *
 * <p>取图链路照它的走（其源码 {@code Main.pickController}/{@code Main.albumArt}
 * 印证）：锁屏进程持有 MEDIA_CONTENT_CONTROL，{@code getActiveSessions(null)} 在
 * 这里直接可用，会话里的封面就是全分辨率位图。
 *
 * <p><b>兜底</b>：任何一步失败（没装、没开、没在放、只给 URI、没权限、组不出来、
 * 应答没到）一律落回已有的那份或壁纸文件，效果与历史版本一致，绝不更糟。HMC 若
 * 改类名导致探测失灵，也只是退回壁纸取景，不会崩。
 */
final class MediaCover {

    /** HMC 的包名前缀：它的 View 的类名都以它开头，这是唯一与它耦合的东西（纯字符串）。 */
    private static final String HMC = "com.os4.musiccover";
    /** 歌词视图全名（诊断位）：它挂进 keyguard_foreground_layer 即歌词页（HDR 高光关时）。 */
    private static final String LYRIC_VIEW = "com.os4.musiccover.LyricView";
    /** 卡片视图全名（精确匹配，不含它的 Wash 内部类，诊断位）：上滑中途会被整层摘掉。 */
    private static final String CARD_VIEW = "com.os4.musiccover.CoverCardLayer";
    /** 探测结果位：树里有 HMC 的任意 View —— 在场旁证 + 首见预热（frostglass5 起不再作门槛）。 */
    static final int HMC_ANY = 1;
    /** 探测结果位（诊断）：歌词视图在树。 */
    static final int HMC_LYRICS = 2;
    /** 探测结果位（诊断）：卡片在树里。 */
    static final int HMC_CARD = 4;
    /** 探测结果位（诊断）：卡片在树里<b>且可见</b>。 */
    static final int HMC_CARD_SHOWN = 8;
    /** 磨砂宽度（px）：HMC frostedFor 把图糊到 36 宽再拉伸铺满 —— 只剩颜色，没有结构。 */
    private static final int FROST_W = 36;
    /** 磨砂的封面摆位偏置：HMC Main.DEFAULT_BIAS 出厂值（它是可调设置，这里取默认）。 */
    private static final float BIAS = 0.34f;
    /** 盒式模糊半径 / 遍数：HMC {@code blur(src, 36, 3, 3)} 同款。 */
    private static final int BLUR_R = 3;
    private static final int BLUR_PASSES = 3;
    /** 封面页糊底宽度 / 半径：HMC {@code blur(bg, 48, 4, 3)}（composeWallpaper 同款）。 */
    private static final int COVER_W = 48;
    private static final int COVER_BLUR_R = 4;
    /** 取系统媒体卡缩略图时，在主线程那边限时等多久（后台线程唯一一次等主线程）。 */
    private static final long THUMB_WAIT_MS = 200L;

    private MediaCover() {
    }

    // ------------------------------------------------------------------ 探测

    /**
     * 扫一遍锁屏树，返回 {@link #HMC_ANY} / {@link #HMC_LYRICS} / {@link #HMC_CARD} /
     * {@link #HMC_CARD_SHOWN} 的按位或。只能在主线程调（读的是视图树）；调用方把它
     * 放在起手/节流点上，帧内不重复走查。遍历中树被改也只是这次判保守，不崩。
     * frostglass5 起树探到只作在场<b>旁证</b>（{@link #noteHmc}）与首见预热的触发，
     * 门槛与页面全听应答的 —— 树摘 ≠ 背景回（found 位与页面位都进诊断日志）。
     */
    static int scan(View node) {
        try {
            return scanInto(node, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int scanInto(View node, int flags) {
        if (node == null) {
            return flags;
        }
        String name = node.getClass().getName();
        if (name.startsWith(HMC)) {
            flags |= HMC_ANY;
            if (LYRIC_VIEW.equals(name)) {
                flags |= HMC_LYRICS;
            } else if (CARD_VIEW.equals(name)) {
                flags |= HMC_CARD;
                if (node.isShown()) {
                    flags |= HMC_CARD_SHOWN;
                }
            }
        }
        if ((flags & HMC_LYRICS) == 0 && node instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) node;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                flags = scanInto(g.getChildAt(i), flags);
                if ((flags & HMC_LYRICS) != 0) {
                    break;
                }
            }
        }
        return flags;
    }

    /**
     * 诊断：树里 HMC 视图的简名与可见性（{@code CoverCardLayer[vis0/shown]}，去重、
     * 最多八种、封顶 360 字符）。只在扫描位发生变化时被调用（见 LockGlassStrip
     * 的 lastScan 节流），稳态零开销；出问题时这一行就能定案「哪类视图在/不在、
     * 显不显」，不用再猜 HMC 内部时序。
     */
    static String hmcDump(View node) {
        StringBuilder sb = new StringBuilder();
        try {
            collectHmc(node, sb, 0);
        } catch (Throwable ignored) {
            // 诊断而已，树变了就半截收尾
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    private static void collectHmc(View node, StringBuilder sb, int depth) {
        if (node == null || depth > 80 || sb.length() > 360) {
            return;
        }
        String name = node.getClass().getName();
        if (name.startsWith(HMC)) {
            String tag = name.substring(name.lastIndexOf('.') + 1)
                    + "[vis" + node.getVisibility()
                    + (node.isShown() ? "/shown" : "") + "]";
            if (sb.indexOf(tag) < 0) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(tag);
            }
        }
        if (node instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) node;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                collectHmc(g.getChildAt(i), sb, depth + 1);
            }
        }
    }

    // ------------------------------------------------------------------ 会话监听

    private static MediaSessionManager sMsm;
    private static MediaController sCurrent;
    /** 换曲/换会话回调（封面可能换了，该重组磨砂）。 */
    private static Runnable sOnChange;
    /** 播/停回调（只换门槛纹理，绝不重组磨砂）。 */
    private static Runnable sOnState;
    private static boolean sWatching;
    /** 会话还活着（在播/缓冲/暂停待续）：磨砂门槛，见 {@link #sessionActive}。 */
    private static volatile boolean sActive;

    private static final MediaSessionManager.OnActiveSessionsChangedListener sSessionsCb =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    rebind(controllers);
                }
            };

    private static final MediaController.Callback sMetaCb = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refreshActive();
            fire();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            // 播/停改的只是「门槛」（会话活不活），纹理换不换由重扫的 applyTexture 定；
            // 磨砂图是同一张专辑的产物，绝不因播/停重组 —— 播停事件比换曲密得多，
            // frostglass2 日志里四次连续重组就是这条回调造成的浪费
            refreshActive();
            Runnable r = sOnState;
            if (r != null) {
                r.run();
            }
        }
    };

    /**
     * 在主线程注册一次会话监听：换曲/换会话回调 {@code onCover}（该按新封面重组磨砂），
     * 播/停回调 {@code onState}（只该重扫换门槛纹理）。两个回调都在主线程。重复调用
     * 只换回调不重复注册 —— 控制器重建后新的条子顶上、旧条子不再被叫。注册失败静默，
     * 取图那条路本身不依赖它。
     */
    static void watch(Context ctx, Runnable onCover, Runnable onState) {
        if (onCover == null) {
            return;
        }
        sOnChange = onCover;
        sOnState = onState;
        if (sWatching) {
            return;
        }
        try {
            sMsm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (sMsm == null) {
                return;
            }
            sWatching = true;
            sMsm.addOnActiveSessionsChangedListener(
                    sSessionsCb, null, new Handler(Looper.getMainLooper()));
            // 监听只在「变化」时响，当前这个会话得自己先绑一次
            rebind(sMsm.getActiveSessions(null));
        } catch (Throwable ignored) {
            // 没权限 / 服务不在：整条链路静默关闭，玻璃退回壁纸取景
            sWatching = false;
            sMsm = null;
        }
    }

    private static void rebind(List<MediaController> controllers) {
        try {
            if (sCurrent != null) {
                sCurrent.unregisterCallback(sMetaCb);
            }
        } catch (Throwable ignored) {
        }
        sCurrent = pick(controllers);
        refreshActive();
        if (sCurrent != null) {
            try {
                sCurrent.registerCallback(sMetaCb, new Handler(Looper.getMainLooper()));
            } catch (Throwable ignored) {
            }
        }
        fire();   // 会话换人本身就是一次变化
    }

    private static void fire() {
        Runnable r = sOnChange;
        if (r != null) {
            r.run();
        }
    }

    /**
     * 会话还「活着」吗 —— 在播 / 缓冲 / 暂停待续（锁屏封面还挂着的状态）。
     *
     * <p>这是磨砂门槛（frostglass3）：播完停死后背景已经回了壁纸，玻璃必须回文件 ——
     * 否则会话早死了、树里还残留 HMC 的 GONE 视图（它只在 context 变化时 removeView），
     * 玻璃就会拿一张死专辑色糊着真壁纸。会话活着时封面才是屏上的主角，玻璃才配取
     * 它的色。状态由 rebind 与两个回调在主线程维护，扫树读的也是主线程，无锁无 binder。
     */
    static boolean sessionActive() {
        return sActive;
    }

    private static void refreshActive() {
        try {
            PlaybackState ps = sCurrent != null ? sCurrent.getPlaybackState() : null;
            int st = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
            sActive = st == PlaybackState.STATE_PLAYING
                    || st == PlaybackState.STATE_PAUSED
                    || st == PlaybackState.STATE_BUFFERING
                    || st == PlaybackState.STATE_CONNECTING;
        } catch (Throwable ignored) {
            sActive = false;
        }
    }

    // ------------------------------------------------------------------ 取图

    /**
     * 从会话列表里挑一个（镜像 HMC 的排法）：正在播放的压倒性优先，其次最近上报过
     * 进度的。多个播放器同时挂着会话时（实测 Apple Music + Salt Player 并存），
     * 「第一个有封面的」会拿错专辑，排法必须跟系统媒体卡一致。
     */
    private static MediaController pick(List<MediaController> cs) {
        MediaController best = null;
        long bestScore = Long.MIN_VALUE;
        if (cs == null) {
            return null;
        }
        for (MediaController c : cs) {
            try {
                PlaybackState ps = c.getPlaybackState();
                if (ps == null) {
                    continue;
                }
                long score = ps.getLastPositionUpdateTime();
                if (ps.getState() == PlaybackState.STATE_PLAYING) {
                    score += (1L << 50);
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            } catch (Throwable ignored) {
                // 单个会话读失败就跳过它
            }
        }
        return best;
    }

    /**
     * 会话封面：ALBUM_ART → ART → iconBitmap；都空再向系统媒体卡的缩略图要。
     */
    private static Bitmap artOf(MediaController c, View tree) {
        try {
            MediaMetadata md = c.getMetadata();
            if (md != null) {
                Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b == null) {
                    b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                }
                if (b == null && md.getDescription() != null) {
                    b = md.getDescription().getIconBitmap();
                }
                if (b != null) {
                    return b;
                }
            }
        } catch (Throwable ignored) {
        }
        return cardThumb(tree);
    }

    /**
     * 系统媒体卡的封面缩略图（只发 artwork URI 的播放器靠它兜底，HMC 同款兜底链）。
     * 视图树归主线程：后台线程 post 过去取、latch 限时等 —— 等不到就当没有。
     */
    private static Bitmap cardThumb(View tree) {
        final View root = tree.getRootView();
        final Bitmap[] out = new Bitmap[1];
        final CountDownLatch done = new CountDownLatch(1);
        try {
            boolean queued = root.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        int id = root.getResources().getIdentifier(
                                "album_art_image", "id", "com.android.systemui");
                        View v = id != 0 ? root.findViewById(id) : null;
                        if (v instanceof ImageView) {
                            android.graphics.drawable.Drawable d =
                                    ((ImageView) v).getDrawable();
                            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                                out[0] = ((android.graphics.drawable.BitmapDrawable) d)
                                        .getBitmap();
                            } else if (d != null) {
                                int dw = d.getIntrinsicWidth();
                                int dh = d.getIntrinsicHeight();
                                if (dw > 0 && dh > 0) {
                                    Bitmap b = Bitmap.createBitmap(
                                            dw, dh, Bitmap.Config.ARGB_8888);
                                    d.setBounds(0, 0, dw, dh);
                                    d.draw(new Canvas(b));
                                    out[0] = b;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                }
            });
            if (!queued) {
                return null;
            }
            done.await(THUMB_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
        return out[0];
    }

    // ------------------------------------------------------ 组图（歌词磨砂 + 封面合成两份）

    /** 一次组出来的两份音乐纹理 —— 各自可独立失败，组不出的那份给 null，另一份照给。 */
    static final class Tex {
        /** 歌词页背景：{@link #frostedFrom}（frostedFor 配方，frostglass3 已验）。 */
        final Bitmap frosted;
        /** 封面页背景：{@link #coverFrom}（composeWallpaper 配方，frostglass4 新增）。 */
        final Bitmap cover;

        Tex(Bitmap frosted, Bitmap cover) {
            this.frosted = frosted;
            this.cover = cover;
        }
    }

    /**
     * 一趟组出两份音乐纹理：取一次封面（链路里最贵的一步：binder 元数据 + 缩略图
     * 限时等主线程），先组歌词磨砂，再组封面合成 —— 两份共用这次取图，换曲只踢
     * 一次线程、只读一次会话。
     *
     * <p>后台线程调用。任何一步失败该份为 null（两份都出不来给全 null），调用方
     * 保持已有纹理 / 壁纸取景 —— 兜底语义不变。
     */
    static Tex compose(Context ctx, View tree, int w, int h) {
        try {
            if (tree == null || w <= 0 || h <= 0) {
                return new Tex(null, null);
            }
            MediaSessionManager msm = sMsm != null ? sMsm
                    : (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) {
                return new Tex(null, null);
            }
            MediaController c = pick(msm.getActiveSessions(null));
            if (c == null) {
                return new Tex(null, null);
            }
            Bitmap art = artOf(c, tree);
            if (art == null || art.isRecycled()
                    || art.getWidth() <= 0 || art.getHeight() <= 0) {
                return new Tex(null, null);
            }
            Bitmap f;
            Bitmap cv;
            try {
                f = frostedFrom(art, w, h);
            } catch (Throwable t) {
                f = null;
            }
            try {
                cv = coverFrom(art, w, h);
            } catch (Throwable t) {
                cv = null;
            }
            return new Tex(f, cv);
        } catch (Throwable t) {
            return new Tex(null, null);
        }
    }

    /**
     * 组一张「歌词页磨砂」纹理：完全照 HMC {@code frostedFor} 的配方走 —— 封面全宽
     * 摆放、上下镜像续满屏幅（{@link #BIAS} = 它的出厂摆位）→ 逐级折半再落到
     * {@link #FROST_W} 宽 → 小图上 {@link #BLUR_PASSES} 遍盒式模糊（半径
     * {@link #BLUR_R}）把结构抹净 → 饱和度 ×1.3（只压暗会让亮封面发灰，它同款）→
     * 0x14 + 0x61 两道压暗（0x14 是它合成图上那层纱，0x61 是磨砂本体，两道都要，
     * 从布局组出来的那份才与从合成图组出来的同色）。
     *
     * <p>刻意停在 FROST_W 宽、不拉回屏幅：饱和 / 压暗 / 拉伸都是逐像素线性运算、
     * 次序可交换（HMC {@code frostedOf} 的注释就是这么论证的），最后由 shader 采样
     * 时拉伸，走的与它同一条双线性 —— 结果同色，还省一张屏幅位图（36x78 ≈ 11KB）。
     */
    private static Bitmap frostedFrom(Bitmap art, int w, int h) {
        Bitmap bg = mirror(art, w, h);
        Bitmap small = shrink(bg, FROST_W);
        bg.recycle();   // shrink 保证返回值与入参不同对象
        boxBlur(small, BLUR_R, BLUR_PASSES);
        Bitmap out = frostTint(small);
        small.recycle();
        return out;
    }

    /**
     * 镜像背景（1/4 屏幅，HMC {@code mirroredBackground} 同式同尺寸）：封面按全宽
     * 摆（方形专辑居中裁切会放大 ~5 倍把画剪烂），位置按 {@link #BIAS} 落在余量里，
     * 上下用<b>镜像</b>续满 —— 镜像而非平移重复，是因为接缝两侧是同一行像素，
     * 连续性是构造出来的。band 钳到 ≥1px：全景封面的 band 不到一行时，重复次数按
     * 1 算才不会漏画下半幅（HMC 同款坑与同款钳法）。
     */
    private static Bitmap mirror(Bitmap src, int w, int h) {
        int bw = Math.max(1, w / 4);
        int bh = Math.max(1, h / 4);
        float band = Math.max(1f, src.getHeight() * (bw / (float) src.getWidth()));
        float top = (bh - band) * BIAS;

        Bitmap bg = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bg);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        RectF dst = new RectF();
        dst.set(0f, top, bw, top + band);
        cv.drawBitmap(src, null, dst, p);
        int tiles = Math.min(64, (int) Math.ceil(bh / band) + 1);
        for (int i = 1; i <= tiles; i++) {
            tile(cv, src, bw, band, top + i * band, (i % 2) == 1, dst, p);
            tile(cv, src, bw, band, top - i * band, (i % 2) == 1, dst, p);
        }
        return bg;
    }

    /** 一张镜像续片：flip 时上下翻转着画，与前一张互为镜像。 */
    private static void tile(Canvas cv, Bitmap src, int bw, float bandH, float y,
                             boolean flip, RectF dst, Paint p) {
        if (y > cv.getHeight() || y + bandH < 0f) {
            return;
        }
        if (!flip) {
            dst.set(0f, y, bw, y + bandH);
            cv.drawBitmap(src, null, dst, p);
            return;
        }
        cv.save();
        cv.translate(0f, y + bandH);
        cv.scale(1f, -1f);
        dst.set(0f, 0f, bw, bandH);
        cv.drawBitmap(src, null, dst, p);
        cv.restore();
    }

    /**
     * 逐级折半再精确落到目标宽（HMC {@code blur} 的前半段）：每级折半都是一次盒式
     * 均值，绝不一次大幅跳采 —— 直接硬缩再拉回去会留块状菱形。返回值保证与入参
     * 不同对象（调用方敢回收入参）。
     */
    private static Bitmap shrink(Bitmap src, int targetW) {
        Bitmap cur = src;
        while (cur.getWidth() / 2 > targetW) {
            Bitmap next = Bitmap.createScaledBitmap(cur,
                    cur.getWidth() / 2, Math.max(1, cur.getHeight() / 2), true);
            if (cur != src) {
                cur.recycle();
            }
            cur = next;
        }
        int th = Math.max(1, Math.round(cur.getHeight() * (targetW / (float) cur.getWidth())));
        if (cur.getWidth() == targetW && cur.getHeight() == th) {
            // 极小窗口才会走到这：createScaledBitmap 同尺寸会原样返回（同对象），
            // 手工画一张新的，维持「返回值 ≠ 入参」的约定
            Bitmap out = Bitmap.createBitmap(targetW, th, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            cv.drawBitmap(cur, 0f, 0f, new Paint(Paint.FILTER_BITMAP_FLAG));
            if (cur != src) {
                cur.recycle();
            }
            return out;
        }
        Bitmap out = Bitmap.createScaledBitmap(cur, targetW, th, true);
        if (cur != src) {
            cur.recycle();
        }
        return out;
    }

    /**
     * 小图上原地盒式模糊（HMC {@code boxH}/{@code boxV} 同构）：滑动窗口均值，边缘
     * 钳到端像素，RGB 各通道独立累加，alpha 钉 0xFF（源图按不透明处理，与它一致）。
     */
    private static void boxBlur(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0 || radius < 1 || passes < 1) {
            return;
        }
        int n = w * h;
        int[] a = new int[n];
        int[] b = new int[n];
        bmp.getPixels(a, 0, w, 0, 0, w, h);
        for (int i = 0; i < passes; i++) {
            boxH(a, b, w, h, radius);
            boxV(b, a, w, h, radius);
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void boxH(int[] src, int[] dst, int w, int h, int r) {
        int win = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int base = y * w;
            int sr = 0;
            int sg = 0;
            int sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[base + Math.min(w - 1, Math.max(0, i))];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[base + x] = 0xFF000000 | ((sr / win) << 16) | ((sg / win) << 8)
                        | (sb / win);
                int o = src[base + Math.min(w - 1, Math.max(0, x - r))];
                int in = src[base + Math.min(w - 1, Math.max(0, x + r + 1))];
                sr += ((in >> 16) & 0xFF) - ((o >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((o >> 8) & 0xFF);
                sb += (in & 0xFF) - (o & 0xFF);
            }
        }
    }

    private static void boxV(int[] src, int[] dst, int w, int h, int r) {
        int win = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0;
            int sg = 0;
            int sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[Math.min(h - 1, Math.max(0, i)) * w + x];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000 | ((sr / win) << 16) | ((sg / win) << 8)
                        | (sb / win);
                int o = src[Math.min(h - 1, Math.max(0, y - r)) * w + x];
                int in = src[Math.min(h - 1, Math.max(0, y + r + 1)) * w + x];
                sr += ((in >> 16) & 0xFF) - ((o >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((o >> 8) & 0xFF);
                sb += (in & 0xFF) - (o & 0xFF);
            }
        }
    }

    /**
     * 饱和 + 两道压暗，在小图上做（HMC {@code frostedOf} 同序同值）：饱和 ×1.3 先走，
     * 0x14 的纱、0x61 的磨砂依次压上。返回新位图，入参由调用方回收。
     */
    private static Bitmap frostTint(Bitmap small) {
        int w = small.getWidth();
        int h = small.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.3f);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        cv.drawBitmap(small, 0f, 0f, p);
        cv.drawColor(0x14000000);
        cv.drawColor(0x61000000);
        return out;
    }

    /**
     * 组一张「封面页壁纸」纹理：完全照 HMC {@code composeWallpaper} 的配方走 ——
     * 镜像铺底（{@link #BIAS} 摆位）→ 糊到 {@link #COVER_W} 宽、盒糊
     * {@link #COVER_BLUR_R} × {@link #BLUR_PASSES} 遍（它的 {@code blur(bg, 48, 4, 3)}
     * 同款）→ 拉伸铺满 → {@code 0x14000000} <b>一道</b>纱（没有磨砂那道 0x61 —— 这
     * 就是封面页比歌词页亮一档的根本原因，frostglass3 拿磨砂顶它的「仍不对」在此）→
     * 按 {@link #BIAS} 摆位叠<b>锐利横带</b>：封面原比例全宽、上下羽化融进糊底。
     *
     * <p>半幅分辨率组（w/2 x h/2）：横带内容是原图直接画的（不是糊底那 48 宽），
     * 而玻璃只是 ~60px 宽的透镜、还要过折射与模糊 —— 半幅与全幅在屏上无差别，内存
     * 省一半（壁纸那份 17MB 已经常驻）。各段<b>比例</b>与它逐位相同：糊宽 48 是按
     * 合成画布算的、两份画布映射同一块屏，屏上颗粒度同样落在 30px；羽化
     * {@code min(120, bandH/4)} 正是全幅 {@code min(240, coverH/4)} 的一半。
     *
     * <p>与磨砂同一条理由，糊底刻意停在 COVER_W 宽不回爬：逐像素线性运算可交换，
     * 最后由 shader 采样时拉伸，走的与它同一条双线性。
     */
    private static Bitmap coverFrom(Bitmap art, int w, int h) {
        int cw = Math.max(2, w / 2);
        int ch = Math.max(2, h / 2);
        Bitmap bg = mirror(art, cw, ch);
        Bitmap soft = shrink(bg, COVER_W);
        bg.recycle();   // shrink 保证返回值与入参不同对象
        boxBlur(soft, COVER_BLUR_R, BLUR_PASSES);
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        cv.drawBitmap(soft, null, new RectF(0f, 0f, cw, ch), p);
        soft.recycle();
        cv.drawColor(0x14000000);
        float bandH = art.getHeight() * (cw / (float) art.getWidth());
        float top = (ch - bandH) * BIAS;
        int bh = Math.max(2, Math.round(bandH));
        int feather = Math.min(120, Math.round(bh / 4f));
        Bitmap band = feathered(art, cw, bh, feather);
        cv.drawBitmap(band, null, new RectF(0f, top, cw, top + bandH), p);
        band.recycle();
        return out;
    }

    /**
     * 上下羽化的横带（HMC {@code feathered} 同式同守卫）：封面先按 band 尺寸铺进
     * 画布，再用 DST_IN 渐变把顶 / 底 {@code feather} 像素淡到全透明 —— 横带因此
     * 没有硬切边，糊底从模糊自然化到锐利（这条羽化在 HMC 自己的屏上也是它的一部分，
     * 玻璃采样必须一并采到，否则横带上下沿会在玻璃里变成一道不存在的棱）。
     */
    private static Bitmap feathered(Bitmap src, int w, int bandH, int feather) {
        Bitmap band = Bitmap.createBitmap(w, bandH, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(band);
        Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
        bc.drawBitmap(src, null, new RectF(0f, 0f, w, bandH), bp);
        if (feather > 0 && feather * 2 <= bandH) {
            Paint mask = new Paint();
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            mask.setShader(new LinearGradient(0f, 0f, 0f, feather,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            bc.drawRect(0f, 0f, w, feather, mask);
            mask.setShader(new LinearGradient(0f, bandH - feather, 0f, bandH,
                    0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
            bc.drawRect(0f, bandH - feather, w, bandH, mask);
        }
        return band;
    }

    // ------------------------------------------------------------------ 页面查询（HMC 公开 op）

    /** 页面位：未知 —— 应答还没到 / 没答上来（纹理按磨砂兑底，见调用方）。 */
    static final int PAGE_UNKNOWN = 0;
    /** 歌词页在屏上（应答 {@code shown=true}）：屏上背景是它推的磨砂。 */
    static final int PAGE_LYRICS = 1;
    /** 封面页（{@code shown=false}）：屏上背景是 composeWallpaper 合成图。 */
    static final int PAGE_COVER = 2;

    private static volatile int sPage = PAGE_UNKNOWN;
    /**
     * 封面模式（应答 {@code cover=}）：关着它就不顶背景、屏上是原壁纸，一切照旧走
     * 壁纸文件。未问到时按开算 —— 与 frostglass3 行为一致，不因多问一手反而变糟。
     */
    private static volatile boolean sCoverMode = true;
    private static long sLastQueryAt;
    private static boolean sNoAnswer;

    /**
     * HMC 在过（frostglass5 的在场真值）。<b>树摘 ≠ 背景回</b> —— 专辑背景顶替在
     * 壁纸进程里，HMC 的 View 挂摘（重锁后不回来、上滑中途被摘、切页离树）与它
     * 无关；frostglass4 实测 51.289 后锁屏树里恒找不到它，拿树当在场玻璃就掉回
     * 壁纸文件（用户看到的「还是壁纸」）。应答到过、或树里见过它的 View，都算
     * 在过：两者都只能随 SystemUI 重启消失，而那时本进程的静态也一起清零 ——
     * 一次性证明，不需要超时。
     */
    private static volatile boolean sHmcSeen;

    /** HMC 模块活着（应答到过 / 树见过）。在场门槛只认它，树探到与否只作旁证。 */
    static boolean hmcAlive() {
        return sHmcSeen;
    }

    /** 树里见到它的 View —— 在场的旁证（应答没来过时的缓冲，一次性）。 */
    static void noteHmc() {
        sHmcSeen = true;
    }

    static int page() {
        return sPage;
    }

    static boolean coverModeOn() {
        return sCoverMode;
    }

    /** 页面名（只进日志与诊断）。 */
    static String pageName(int page) {
        return page == PAGE_LYRICS ? "歌词" : page == PAGE_COVER ? "封面" : "未知";
    }

    /**
     * 问一次 HMC 现在在哪一页 —— 走它的<b>公开 op 协议</b>（adb {@code am broadcast}
     * 同一条链路里的只读诊断项 {@code lyricstate}）：有序广播
     * {@code com.os4.musiccover.PROBE} → 它注册在本进程（SystemUI）的 ProbeReceiver
     * 收下，把 {@code LockLyrics.describe()} 写进 result data 回来。串里
     * {@code " shown="} 是 {@code wantsShown()}（歌词带此刻在不在屏上）的真值、
     * {@code " cover="} 是封面模式。
     *
     * <p><b>为什么必须问它本人</b>：装机的 HMC 是 R8 混淆的 release 构建，树里只有
     * {@code com.os4.musiccover.x}，按类名认页面永远不成立；上滑中途它又把视图摘出
     * 树（实测日志 found 1→0），树里看的都是瞬时。op 应答是它自己状态机给的答案，
     * 与混淆无关、与摘树无关 —— 无歌词的歌、双指点击藏歌词这些「留在封面页」的
     * 情形，它也一并答对（shown 会照实说 false）。
     *
     * <p>准入照它的规矩走：我们发自 SystemUI 进程，sender identity = myUid，它的
     * {@code ProbeGuard.check()} 对自家进程直接放行；两处都按它 ModuleBridge 的发
     * 包形状写（setPackage + FOREGROUND flag + shareIdentity）。镜像公开链路、
     * 零反射、纯读（lyricstate 是它自己的「for reading state」项），不碰它任何
     * 内部类与写项。
     *
     * <p>200ms 节流（起手沿 / 换曲 / 装上各问一次，稳态不刷）。应答回主线程写完
     * 缓存叫 {@code onAnswer}（重挂纹理，毫秒级、玻璃还没显形，切页闪不出来）。
     * 发不出去 / 被拒 / 超时：sPage 维持未知，纹理落磨砂兑底，绝不更糟 —— 只在
     * 第一次答不上来时打一行日志。
     */
    static void queryPage(Context ctx, Runnable onAnswer) {
        if (ctx == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastQueryAt < 200L) {
            return;
        }
        sLastQueryAt = now;
        try {
            Intent q = new Intent("com.os4.musiccover.PROBE");
            q.setPackage("com.android.systemui");
            q.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            q.putExtra("op", "lyricstate");
            android.app.BroadcastOptions bo = android.app.BroadcastOptions.makeBasic();
            bo.setShareIdentityEnabled(true);   // 它的准入看 sender identity，不带就拒
            ctx.sendOrderedBroadcast(q, null, bo.toBundle(), new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent r) {
                    boolean ok = parsePage(getResultData());
                    if (!ok && !sNoAnswer) {
                        sNoAnswer = true;
                        ModuleLog.d("glass: HMC 页面应答缺失（协议被拒/版本不同），"
                                + "纹理维持磨砂兑底");
                    }
                    if (onAnswer != null) {
                        onAnswer.run();
                    }
                }
            }, new Handler(Looper.getMainLooper()), 0, null, null);
        } catch (Throwable ignored) {
            // 极端情况发不出去：页面维持未知，兜底语义不变
        }
    }

    /**
     * 解析 {@code lyricstate} 应答。两个键都按<b>前导空格</b>锚定：串里另有
     * {@code card=shown=}（大媒体卡，两页都可见）与 {@code container=…/shown=}
     * （歌词容器），都不是页面位；空格恰好只命中 {@code LockLyrics.describe()} 自己
     * 那两个字段。前缀只取首个命中位（其后的 LyricWindow/view 段不在解析范围）。
     * 返回是否解析成功。
     */
    private static boolean parsePage(String s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        // 收到应答 = 模块活着（键位不同也算：在场成立，页面落未知兑底）
        sHmcSeen = true;
        boolean modeBefore = sCoverMode;
        int mode = s.indexOf(" cover=");
        if (mode >= 0) {
            sCoverMode = s.regionMatches(mode + 7, "true", 0, 4);
        }
        int k = s.indexOf(" shown=");
        if (k < 0) {
            return false;
        }
        int page = s.regionMatches(k + 7, "true", 0, 4) ? PAGE_LYRICS : PAGE_COVER;
        if (page != sPage || modeBefore != sCoverMode) {
            // 附原文片段：两个键的真实写法当场可见，解析对不对一行定案
            String frag = (mode >= 0
                    ? s.substring(mode, Math.min(mode + 12, s.length())) : "cover=?")
                    + " / " + (k >= 0
                    ? s.substring(k, Math.min(k + 14, s.length())) : "shown=?");
            ModuleLog.d("glass: HMC 页面=" + pageName(page)
                    + " 封面模式=" + (sCoverMode ? "开" : "关")
                    + " 应答[" + frag + "]");
        }
        sPage = page;
        return true;
    }
}
