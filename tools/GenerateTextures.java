import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * DeltaNexus GUI 纹理生成器（阶段 5：深空灰主背景 + 亮橙 #FF8800 高亮，硬朗边框）。
 * 运行: javac -d out tools/GenerateTextures.java && java -cp out GenerateTextures
 */
public class GenerateTextures {

    static final Color BG = new Color(0x14151C);
    static final Color PANEL = new Color(0x1B1C26);
    static final Color BORDER = new Color(0x3A3A4A);
    static final Color ORANGE = new Color(0xFF8800);
    static final Color ORANGE_DARK = new Color(0xCC6600);
    static final Color ORANGE_LIGHT = new Color(0xFFAA33);
    static final Color RED = new Color(0xCC3333);

    static final File OUT = new File("src/main/resources/assets/deltanexus/textures/gui");

    public static void main(String[] args) throws IOException {
        OUT.mkdirs();

        // 槽位 18x18
        BufferedImage slot = newImage(18, 18);
        fill(slot, PANEL);
        rect(slot, BORDER);
        write(slot, "slot.png");

        // 锁定槽位 18x18：红斜线
        BufferedImage locked = newImage(18, 18);
        fill(locked, PANEL);
        rect(locked, BORDER);
        Graphics2D g = locked.createGraphics();
        g.setColor(new Color(0x66, 0x22, 0x22));
        for (int x = -18; x < 36; x += 4) {
            g.drawLine(x, 18, x + 18, 0);
        }
        g.dispose();
        write(locked, "slot_locked.png");

        // 锁图标 16x16
        BufferedImage lock = newImage(16, 16);
        Graphics2D lg = lock.createGraphics();
        lg.setColor(ORANGE);
        lg.fillRoundRect(3, 7, 10, 8, 2, 2);
        lg.fillRoundRect(5, 4, 6, 4, 3, 3); // 锁梁
        lg.setColor(ORANGE_DARK);
        lg.fillRect(7, 10, 2, 3);
        lg.dispose();
        write(lock, "lock_icon.png");

        // 按钮 162x18（亮橙，可拉伸）
        BufferedImage btn = newImage(162, 18);
        Graphics2D bg2 = btn.createGraphics();
        bg2.setPaint(new GradientPaint(0, 0, ORANGE_LIGHT, 0, 18, ORANGE_DARK));
        bg2.fillRect(0, 0, 162, 18);
        bg2.setColor(BORDER);
        bg2.drawRect(0, 0, 161, 17);
        bg2.dispose();
        write(btn, "button.png");

        // 进度分段 10x10
        BufferedImage on = newImage(10, 10);
        fill(on, ORANGE);
        rect(on, ORANGE_LIGHT);
        write(on, "progress_on.png");
        BufferedImage off = newImage(10, 10);
        fill(off, new Color(0x2A2B36));
        rect(off, BORDER);
        write(off, "progress_off.png");

        // 仓库 GUI 512x512（实际绘制区 400x232：左区玩家面板 + 右区 9x9 仓库 + 左下升级面板）
        BufferedImage wh = newImage(512, 512);
        fill(wh, BG);
        panel(wh, 0, 0, 400, 232);
        // 左区：盔甲 4 竖排 + 副手 + 背包 3x9 + 快捷栏
        for (int i = 0; i < 4; i++) {
            frame(wh, 8, 20 + i * 18);
        }
        frame(wh, 8, 96); // 副手
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                frame(wh, 52 + c * 18, 20 + r * 18);
            }
        }
        for (int c = 0; c < 9; c++) {
            frame(wh, 52 + c * 18, 78);
        }
        // 右区：仓库 9x9 无框（按解锁动态绘制），分隔线；底部升级面板分隔线
        Graphics2D wg = wh.createGraphics();
        wg.setColor(BORDER);
        wg.drawLine(218, 14, 218, 186);
        wg.drawLine(8, 160, 392, 160); // 升级面板分隔线
        wg.dispose();
        write(wh, "warehouse_gui.png");

        // 制造台 GUI 256x240（左配方区 + 右任务区）
        BufferedImage mf = newImage(256, 256);
        fill(mf, BG);
        panel(mf, 0, 0, 256, 240);
        line(mf, 124, 24, 124, 214, BORDER);
        line(mf, 8, 24, 248, 24, BORDER);
        write(mf, "manufacture_gui.png");

        // 配方编辑 GUI
        BufferedImage re = newImage(256, 256);
        fill(re, BG);
        panel(re, 0, 0, 176, 240);
        // 背包物品条 3x9
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                frame(re, 8 + c * 18, 190 + r * 18);
            }
        }
        write(re, "recipe_edit_gui.png");

        // 管理面板 GUI 256x192
        BufferedImage ad = newImage(256, 256);
        fill(ad, BG);
        panel(ad, 0, 0, 256, 192);
        line(ad, 8, 34, 248, 34, BORDER);
        write(ad, "admin_gui.png");

        System.out.println("Textures generated -> " + OUT.getAbsolutePath());
    }

    static BufferedImage newImage(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    static void fill(BufferedImage img, Color c) {
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.dispose();
    }

    static void rect(BufferedImage img, Color c) {
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.drawRect(0, 0, img.getWidth() - 1, img.getHeight() - 1);
        g.dispose();
    }

    static void panel(BufferedImage img, int x, int y, int w, int h) {
        Graphics2D g = img.createGraphics();
        g.setColor(BG);
        g.fillRect(x, y, w, h);
        g.setColor(BORDER);
        g.drawRect(x, y, w - 1, h - 1);
        g.dispose();
    }

    static void frame(BufferedImage img, int x, int y) {
        Graphics2D g = img.createGraphics();
        g.setColor(PANEL);
        g.fillRect(x, y, 18, 18);
        g.setColor(BORDER);
        g.drawRect(x, y, 17, 17);
        g.dispose();
    }

    static void line(BufferedImage img, int x1, int y1, int x2, int y2, Color c) {
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.drawLine(x1, y1, x2, y2);
        g.dispose();
    }

    /** 玩家背包 27 格（3x9，y 起 140）。 */
    static void drawInventorySlots(BufferedImage img, int ox, int oy) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                frame(img, ox + c * 18, oy + r * 18);
            }
        }
    }

    static void drawHotbar(BufferedImage img, int ox, int oy) {
        for (int c = 0; c < 9; c++) {
            frame(img, ox + c * 18, oy);
        }
    }

    static void write(BufferedImage img, String name) throws IOException {
        ImageIO.write(img, "png", new File(OUT, name));
    }
}
