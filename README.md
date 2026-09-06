# 函数绘图仪 FunctionPlotter

纯 JDK(Swing)实现的函数绘图器,零第三方依赖,单文件源码。

## 快速开始

```bat
:: 方式一:双击
run.bat

:: 方式二:直接运行 JAR
java -jar FunctionPlotter.jar

:: 方式三:源码编译运行
javac -encoding UTF-8 FunctionPlotter.java
java -cp . FunctionPlotter

:: 方式四:命令行求值(无界面)
java -jar FunctionPlotter.jar eval "sin(x)+cos(x)" 1.5708
```

## 功能特性

- 一次绘制多条函数,每条一行,实时编辑自动重绘
- 支持直角坐标 `y=f(x)` 与极坐标 `r=f(t)`(`r=...` 开头自动识别)
- 自动网格、刻度、坐标轴标注,NaN/无穷自动断线
- 调色板配色 + 图例(函数出错会在图例标 ⚠ 并显示原因)

## 表达式语法

| 类别 | 说明 |
| ---- | ---- |
| 运算 | `+ - * / % ^`(乘方为右结合:`2^3^2 = 512`) |
| 隐式乘法 | `2x^2`、`2sin(x)`、`(x)(x+1)`、`sin(x)cos(x)`,也支持 `2 3` |
| 负号 | 一元负号与负指数:`-x^2`、`x^-2` |
| 函数 | `sin cos tan asin acos atan sinh cosh tanh sqrt abs ln log exp floor ceil round sign` |
| 多参函数 | `min(...)` `max(...)` `pow(a,b)` `hypot(a,b)` |
| 常量 | `pi`、`e` |
| 变量 | `x`(直角坐标)、`t`(极坐标) |

### 示例

```text
y=sin(x)
y=cos(x)
y=tan(x)/2
y=x^2/8-1
r=1+cos(t)          # 心形线
r=sin(3t)           # 三叶玫瑰线
```

## 鼠标交互

| 操作 | 效果 |
| ---- | ---- |
| 左键拖拽 | 平移视图 |
| 滚轮 | 以鼠标位置为中心缩放 |
| R 键 / 「复位视图」按钮 | 回到初始视角 |
| 鼠标移动 | 底部状态栏实时显示世界坐标 |

## 命令行求值

```bat
java -jar FunctionPlotter.jar eval "表达式" [x值]
```

- 省略 x 值默认 0;多行表达式按行分别求值。
- 非零退出码表示语法错误,错误信息打印到 stderr。

## 文件说明

```
FunctionPlotter.java   源码(单文件,UTF-8)
FunctionPlotter.jar    可执行 JAR(main-class: FunctionPlotter)
run.bat                双击启动脚本(自动编译 + 运行)
```

## 环境要求

- JDK 8 及以上(开发验证于 JDK 21.0.7)
- Windows / Linux / macOS 均可用