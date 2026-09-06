import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.*;
import java.util.List;

/**
 * 函数绘图仪 FunctionPlotter
 *
 * 纯 JDK(Swing) 实现,零第三方依赖。
 * 用法:
 *   javac FunctionPlotter.java
 *   java  FunctionPlotter                   启动图形界面
 *   java  FunctionPlotter eval "sin(x)+cos(x)" 1.5708   命令行求值
 *
 * 支持:
 *   - y=f(x) 与 r=f(t) 极坐标(以 r= 开头)
 *   - 运算  + - * / % ^(乘方,右结合)
 *    隐式乘法:  2x^2、2sin(x)、(x)(x+1)、sin(x)cos(x)
 *   - 函数: sin cos tan asin acos atan sinh cosh tanh
 *           sqrt abs ln log exp floor ceil round sign
 *           min(...) max(...) pow(a,b) hypot(a,b)
 *   - 常量: pi e  变量: x(或极坐标参数 t)
 *
 * 交互: 左键拖拽平移, 滚轮缩放(以鼠标为中心), R 键重置视图。
 */
public class FunctionPlotter {

    // ---------------- 表达式引擎 ----------------

    public interface Node {
        double eval(double v);
    }

    private static Node num(double d) { return x -> d; }
    private static Node var() { return x -> x; }
    private static Node uop(java.util.function.DoubleUnaryOperator f, Node a) {
        return x -> f.applyAsDouble(a.eval(x));
    }
    private static Node bop(java.util.function.DoubleBinaryOperator f, Node a, Node b) {
        return x -> f.applyAsDouble(a.eval(x), b.eval(x));
    }

    // 单趟递归下降解析器,直接在源字符串上操作
    private static class Parsers {
        final String src;
        int i = 0;
        Parsers(String src) { this.src = src; }

        // tokens parsed from the stream; we implement recursive descent directly on source.
        private char peek() {
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
            return i < src.length() ? src.charAt(i) : '\0';
        }
        private boolean eat(char c) {
            char p = peek();
            if (p == c) { i++; return true; }
            return false;
        }

        // number literal
        private double readNumber() {
            int st = i;
            while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
            if (i < src.length() && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                i++;
                if (i < src.length() && (src.charAt(i) == '+' || src.charAt(i) == '-')) i++;
                while (i < src.length() && Character.isDigit(src.charAt(i))) i++;
            }
            return Double.parseDouble(src.substring(st, i));
        }
        private String readIdent() {
            int st = i;
            while (i < src.length() && (Character.isLetter(src.charAt(i)) || Character.isDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
            return src.substring(st, i);
        }

        Node expr() {
            Node a = term();
            for (;;) {
                if (eat('+')) a = bop((x, y) -> x + y, a, term());
                else if (eat('-')) a = bop((x, y) -> x - y, a, term());
                else return a;
            }
        }

        Node term() {
            Node a = unary();
            for (;;) {
                if (eat('*')) a = bop((x, y) -> x * y, a, unary());
                else if (eat('/')) a = bop((x, y) -> x / y, a, unary());
                else if (eat('%')) a = bop((x, y) -> x % y, a, unary());
                else return a;
            }
        }

        Node unary() {
            if (eat('-')) return uop(v -> -v, unary());
            if (eat('+')) return unary();
            return factor();
        }

        // implicit multiplication: product of powers
        Node factor() {
            Node a = power();
            for (;;) {
                char p = peek();
                if (p == '(' || p == '.' || Character.isDigit(p) || Character.isLetter(p) || p == '_') {
                    Node b = power();
                    a = bop((x, y) -> x * y, a, b);
                } else break;
            }
            return a;
        }

        Node power() {
            Node base = primary();
            if (eat('^')) {
                // right assoc: x^y^z = x^(y^z); allow unary exponent: x^-2
                return bop(Math::pow, base, unary());
            }
            return base;
        }

        Node primary() {
            char p = peek();
            if (p == '(') {
                i++;
                Node inner = expr();
                while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
                if (i >= src.length() || src.charAt(i) != ')') throw new IllegalArgumentException("缺少右括号 ')'");
                i++;
                return inner;
            }
            if (Character.isDigit(p) || p == '.') return num(readNumber());
            if (Character.isLetter(p) || p == '_') {
                String id = readIdent();
                while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
                if (i < src.length() && src.charAt(i) == '(') return funcall(id);
                switch (id) {
                    case "x": case "t": return var();
                    case "pi": return num(Math.PI);
                    case "e": return num(Math.E);
                    default: throw new IllegalArgumentException("未知名称 '" + id + "'");
                }
            }
            throw new IllegalArgumentException("意外的字符 '" + p + "'");
        }

        // function call id(args)
        Node funcall(String id) {
            if (i >= src.length() || src.charAt(i) != '(') throw new IllegalStateException("函数缺参数");
            i++;
            List<Node> args = new ArrayList<>();
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
            if (i < src.length() && src.charAt(i) == ')') { i++; return applyFn(id, args); }
            for (;;) {
                args.add(expr());
                char p = peek();
                if (p == ',') { i++; continue; }
                if (p == ')') { i++; break; }
                throw new IllegalArgumentException("函数参数以逗号分隔,缺少 ')'");
            }
            return applyFn(id, args);
        }

        Node applyFn(String id, List<Node> a) {
            switch (id) {
                case "sin": require(a, 1, id); return uop(Math::sin, a.get(0));
                case "cos": require(a, 1, id); return uop(Math::cos, a.get(0));
                case "tan": require(a, 1, id); return uop(Math::tan, a.get(0));
                case "asin": require(a, 1, id); return uop(Math::asin, a.get(0));
                case "acos": require(a, 1, id); return uop(Math::acos, a.get(0));
                case "atan": require(a, 1, id); return uop(Math::atan, a.get(0));
                case "sinh": require(a, 1, id); return uop(Math::sinh, a.get(0));
                case "cosh": require(a, 1, id); return uop(Math::cosh, a.get(0));
                case "tanh": require(a, 1, id); return uop(Math::tanh, a.get(0));
                case "sqrt": require(a, 1, id); return uop(Math::sqrt, a.get(0));
                case "abs": require(a, 1, id); return uop(Math::abs, a.get(0));
                case "ln": require(a, 1, id); return uop(Math::log, a.get(0));
                case "log": require(a, 1, id); return uop(x -> Math.log(x) / Math.log(10), a.get(0));
                case "exp": require(a, 1, id); return uop(Math::exp, a.get(0));
                case "floor": require(a, 1, id); return uop(Math::floor, a.get(0));
                case "ceil": require(a, 1, id); return uop(Math::ceil, a.get(0));
                case "round": require(a, 1, id); return uop(Math::round, a.get(0));
                case "sign": require(a, 1, id); return uop(v -> v > 0 ? 1 : (v < 0 ? -1 : 0), a.get(0));
                case "pow": require(a, 2, id); return bop(Math::pow, a.get(0), a.get(1));
                case "hypot": require(a, 2, id); return bop(Math::hypot, a.get(0), a.get(1));
                case "min": if (a.isEmpty()) throw new IllegalArgumentException("函数 min 至少需要 1 个参数"); return x -> { double m = Double.POSITIVE_INFINITY; for (Node n : a) m = Math.min(m, n.eval(x)); return m; };
                case "max": if (a.isEmpty()) throw new IllegalArgumentException("函数 max 至少需要 1 个参数"); return x -> { double m = Double.NEGATIVE_INFINITY; for (Node n : a) m = Math.max(m, n.eval(x)); return m; };
                default: throw new IllegalArgumentException("未知函数 '" + id + "'");
            }
        }

        void require(List<Node> a, int n, String id) {
            if (a.size() != n) throw new IllegalArgumentException("函数 " + id + " 需要 " + n + " 个参数");
        }
    }

    // Compiled curve: destructive parser that uses its own source position
    static class Curve {
        final String text;
        final boolean polar;
        final Node fn;

        Curve(String text) {
            String t = text;
            boolean isPolar = false;
            String lower = t.toLowerCase().trim();
            // strip "y=", "f(x)=", "g(x)=", "r=", "r(t)="
            t = t.trim();
            int eq = t.indexOf('=');
            if (eq > 0) {
                String lhs = t.substring(0, eq).trim();
                String rhs = t.substring(eq + 1).trim();
                if (lhs.equals("y") || lhs.equals("f") || lhs.equals("f(x)") ||
                    lhs.equals("g") || lhs.equals("g(x)") || lhs.equals("h(x)") || lhs.equals("h")) {
                    t = rhs;
                } else if (lhs.equals("r") || lhs.equals("r(t)")) {
                    t = rhs;
                    isPolar = true;
                } else if (lhs.matches("[a-zA-Z]")) {
                    t = rhs; // treat "K=..." as y=...
                }
            }
            if (t.isEmpty()) throw new IllegalArgumentException("空表达式");
            this.text = text;
            this.polar = isPolar;
            this.fn = new Parsers(t).expr();
        }
    }

    static List<Curve> compile(String source) {
        List<Curve> out = new ArrayList<>();
        if (source == null) return out;
        for (String line : source.split("\n")) {
            if (line.trim().isEmpty()) continue;
            out.add(new Curve(line));
        }
        return out;
    }

    // ---------------- CLI 求值 ----------------

    static void runEval(String[] args) {
        try {
            String code = args[1];
            double v = args.length >= 3 ? Double.parseDouble(args[2]) : 0.0;
            for (String line : code.split("\n")) {
                if (line.trim().isEmpty()) continue;
                Curve c = new Curve(line);
                double r = c.fn.eval(v);
                System.out.println(line + "  @ " + v + " = " + r);
            }
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }

    // ---------------- 绘图面板 ----------------

    static class PlotPanel extends JPanel {
        final List<Curve> curves = new ArrayList<>();
        final Map<Curve, Color> colors = new HashMap<>();
        final Map<Curve, String> errors = new HashMap<>();
        double cx = 0, cy = 0, ppu = 0;            // 世界中心 与 每单位像素数
        int lastX, lastY;
        String hover = "";

        static final Color[] PALETTE = {
            new Color(0xE63B2E), new Color(0x2E86DE), new Color(0x28B463),
            new Color(0xF39C12), new Color(0x8E44AD), new Color(0x16A085),
            new Color(0xC0392B), new Color(0x7D3C98), new Color(0x87421F),
            new Color(0x4A2C8A)
        };

        PlotPanel() {
            setBackground(new Color(0xFAFAF7));
            setPreferredSize(new Dimension(1000, 680));
            MouseAdapter ma = new MouseAdapter() {
                public void mousePressed(MouseEvent e) { lastX = e.getX(); lastY = e.getY(); }
                public void mouseDragged(MouseEvent e) {
                    if (ppu <= 0) return;
                    cx -= (e.getX() - lastX) / ppu;
                    cy += (e.getY() - lastY) / ppu;
                    lastX = e.getX(); lastY = e.getY();
                    repaint();
                }
                public void mouseMoved(MouseEvent e) {
                    if (ppu <= 0) return;
                    double wx = cx + (e.getX() - getWidth() / 2.0) / ppu;
                    double wy = cy - (e.getY() - getHeight() / 2.0) / ppu;
                    hover = String.format(Locale.ROOT, "x=%.4f  y=%.4f", wx, wy);
                    repaint();
                }
                public void mouseWheelMoved(MouseWheelEvent e) {
                    double f = e.getPreciseWheelRotation() > 0 ? 1 / 1.15 : 1.15;
                    double np = Math.max(0.005, Math.min(5e6, ppu * f));
                    if (Math.abs(np / ppu - 1) < 1e-9) return;
                    // 以鼠标为中心缩放
                    double wx = cx + (e.getX() - getWidth() / 2.0) / ppu;
                    double wy = cy - (e.getY() - getHeight() / 2.0) / ppu;
                    ppu = np;
                    cx = wx - (e.getX() - getWidth() / 2.0) / ppu;
                    cy = wy + (e.getY() - getHeight() / 2.0) / ppu;
                    repaint();
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
        }

        void setCurves(List<Curve> list) {
            synchronized (curves) {
                curves.clear(); colors.clear(); errors.clear();
                int ci = 0;
                for (Curve c : list) {
                    curves.add(c);
                    colors.put(c, PALETTE[ci++ % PALETTE.length]);
                }
            }
            repaint();
        }

        double sx(double wx) { return getWidth() / 2.0 + (wx - cx) * ppu; }
        double sy(double wy) { return getHeight() / 2.0 - (wy - cy) * ppu; }
        double wx(double sx) { return cx + (sx - getWidth() / 2.0) / ppu; }

        void resetView() {
            cx = 0; cy = 0;
            ppu = Math.max(1, getWidth() / 20.0);
            repaint();
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            if (ppu <= 0) ppu = Math.max(1, w / 20.0);

            drawGrid(g2, w, h);
            synchronized (curves) {
                for (Curve c : curves) {
                    String err = errors.get(c);
                    if (err != null) continue;
                    Color col = colors.get(c);
                    g2.setColor(col);
                    try {
                        if (c.polar) drawPolar(g2, c, w, h, col);
                        else drawCart(g2, c, w, h, col);
                    } catch (Throwable t) {
                        errors.put(c, t.getMessage());
                    }
                }
            }
            drawLegend(g2);
            g2.setColor(new Color(0x777777));
            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g2.drawString(hover, 8, h - 8);
        }

        void drawGrid(Graphics2D g2, int w, int h) {
            double step = niceStep(ppu);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));

            // 网格
            g2.setColor(new Color(0xE8E8E0));
            g2.setStroke(new BasicStroke(1f));
            double x0 = Math.floor(wx(0) / step) * step;
            for (double x = x0; x < wx(w) + step; x += step) {
                int px = (int) Math.round(sx(x));
                if (px < 0 || px > w) continue;
                g2.setColor(new Color(0xE0E0D8));
                g2.draw(new Line2D.Double(px, 0, px, h));
                g2.setColor(new Color(0x888888));
                g2.drawString(fmt(x), px + 3, (int) sy(0) - 4);
            }
            double y0 = Math.floor((cy + h / 2.0 / ppu) / step) * step;
            for (double y = y0; y < cy + h / 2.0 / ppu + step; y += step) {
                int py = (int) Math.round(sy(y));
                if (py < 0 || py > h) continue;
                g2.setColor(new Color(0xE0E0D8));
                g2.draw(new Line2D.Double(0, py, w, py));
                g2.setColor(new Color(0x888888));
                g2.drawString(fmt(y), (int) sx(0) + 4, py - 3);
            }

            // 坐标轴
            g2.setColor(new Color(0x555555));
            g2.setStroke(new BasicStroke(1.6f));
            int ax = (int) Math.round(sx(0));
            int ay = (int) Math.round(sy(0));
            if (ax >= 0 && ax <= w) g2.draw(new Line2D.Double(ax, 0, ax, h));
            if (ay >= 0 && ay <= h) g2.draw(new Line2D.Double(0, ay, w, ay));
        }

        void drawCart(Graphics2D g2, Curve c, int w, int h, Color col) {
            Path2D.Double path = null;
            for (int px = 0; px <= w; px++) {
                double wx = wx(px);
                double y;
                try { y = c.fn.eval(wx); } catch (Throwable t) { y = Double.NaN; }
                boolean ok = Double.isFinite(y) && Math.abs(y) < 1e7;
                if (ok) {
                    double py = sy(y);
                    if (py < -2e6 || py > 2e6) ok = false;
                    if (ok && path == null) path = new Path2D.Double();
                    if (ok) {
                        if (path.getCurrentPoint() == null) path.moveTo(px, py);
                        else path.lineTo(px, py);
                    }
                }
                if (!ok && path != null) {
                    g2.draw(path); path = null;
                }
            }
            if (path != null) g2.draw(path);
        }

        void drawPolar(Graphics2D g2, Curve c, int w, int h, Color col) {
            Path2D.Double path = new Path2D.Double();
            int N = 900;
            double lastX = Double.NaN, lastY = Double.NaN;
            for (int i = 0; i <= N; i++) {
                double t = 2 * Math.PI * i / N;
                double r;
                try { r = c.fn.eval(t); } catch (Throwable th) { r = Double.NaN; }
                if (Double.isFinite(r) && Math.abs(r) < 1e6) {
                    double wx = r * Math.cos(t), wy = r * Math.sin(t);
                    double px = sx(wx), py = sy(wy);
                    if (Double.isFinite(px) && Double.isFinite(py) &&
                        Math.abs(px) < 1e7 && Math.abs(py) < 1e7) {
                        if (Double.isFinite(lastX)) path.lineTo(px, py);
                        else path.moveTo(px, py);
                        lastX = px; lastY = py;
                        continue;
                    }
                }
                lastX = lastY = Double.NaN;
            }
            g2.draw(path);
        }

        void drawLegend(Graphics2D g2) {
            int y = 12;
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            synchronized (curves) {
                for (Curve c : curves) {
                    String err = errors.get(c);
                    String label = c.text.length() > 34 ? c.text.substring(0, 33) + "…" : c.text;
                    if (err != null) label = label + "  ⚠ " + err;
                    g2.setColor(new Color(255, 255, 255, 210));
                    int tw = g2.getFontMetrics().stringWidth(label);
                    g2.fillRoundRect(10, y - 10, tw + 18, 16, 6, 6);
                    g2.setColor(colors.get(c));
                    g2.fillOval(14, y - 6, 10, 10);
                    g2.setColor(err != null ? new Color(0xB00020) : new Color(0x333333));
                    g2.drawString(label, 29, y);
                    y += 18;
                }
            }
        }
    }

    static String fmt(double d) {
        double a = Math.abs(d);
        if (a != 0 && (a >= 1e6 || a < 1e-3)) return String.format(Locale.ROOT, "%.1e", d);
        if (Math.abs(d - Math.round(d)) < 1e-9) return String.format(Locale.ROOT, "%.0f", d);
        return String.format(Locale.ROOT, "%.4g", d);
    }

    static double niceStep(double ppu) {
        double raw = 80.0 / ppu;           // 目标: 网格约 80px
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double f = norm < 1.5 ? 1 : norm < 3.5 ? 2 : norm < 7.5 ? 5 : 10;
        return f * mag;
    }

    // ---------------- GUI ----------------

    static JLabel status;
    static PlotPanel plot;
    static JTextArea input;

    static void setCurvesFromInput() {
        List<Curve> list;
        try {
            list = compile(input.getText());
            status.setText("已绘制 " + list.size() + " 条曲线");
            status.setForeground(new Color(0x1B7D3A));
        } catch (Exception e) {
            status.setText("语法错误: " + e.getMessage());
            status.setForeground(new Color(0xB00020));
            return;
        }
        plot.setCurves(list);
    }

    static void createGui() {
        JFrame frame = new JFrame("函数绘图仪 FunctionPlotter  — 纯 JDK / 零依赖");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        plot = new PlotPanel();
        plot.setCurves(compile(
            "y=sin(x)\n" +
            "y=cos(x)\n" +
            "y=tan(x)/2\n" +
            "y=sqrt(max(0,1-x^2))\n" +
            "y=x^2/8-1"
        ));
        plot.resetView();

        // 控制条
        input = new JTextArea(4, 40);
        input.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        input.setText(
            "y=sin(x)\n" +
            "y=cos(x)\n" +
            "y=tan(x)/2\n" +
            "y=sqrt(max(0,1-x^2))\n" +
            "y=x^2/8-1"
        );
        input.setBorder(BorderFactory.createTitledBorder("函数(每行一条;默认 y=f(x),极坐标用 r= 开头;支持 2x^2 / sin(x)cos(x) 隐式乘法)"));
        input.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { setCurvesFromInput(); }
            public void removeUpdate(DocumentEvent e) { setCurvesFromInput(); }
            public void changedUpdate(DocumentEvent e) { setCurvesFromInput(); }
        });

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.add(new JScrollPane(input), BorderLayout.CENTER);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton reset = new JButton("复位视图 R");
        reset.addActionListener(e -> plot.resetView());
        JButton fit = new JButton("示例函数");
        fit.addActionListener(e -> input.setText(
            "y=sin(x)\n" +
            "r=1+cos(t)\n" +
            "y=cos(sqrt(x^2))\n" +
            "y=x*sin(x)/4\n" +
            "r=sin(3t)"
        ));
        btnRow.add(reset); btnRow.add(fit);
        inputPanel.add(btnRow, BorderLayout.SOUTH);

        // 快捷键
        InputMap im = plot.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = plot.getActionMap();
        im.put(KeyStroke.getKeyStroke('R'), "reset");
        am.put("reset", new AbstractAction() { public void actionPerformed(ActionEvent e) { plot.resetView(); } });

        status = new JLabel(" ");
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        frame.setLayout(new BorderLayout());
        frame.add(inputPanel, BorderLayout.NORTH);
        frame.add(plot, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        if (args.length >= 2 && args[0].equals("eval")) {
            runEval(args);
            return;
        }
        SwingUtilities.invokeLater(FunctionPlotter::createGui);
    }
}