# -*- coding: utf-8 -*-
"""
Быстрое сжатие GIF для эмоций Meme.

Два режима:
  1. Двойной клик (или `python shrink_gif.py`) — окно: выбери GIF-файлы, ширину, нажми «Сжать».
  2. Перетащи .gif на shrink_gif.bat (или `python shrink_gif.py файл.gif [-w 320] [-c 255]
     [--drop-half] [-o выход.gif]`) — сожмёт с теми же настройками из консоли.

Что делает: уменьшает кадры до заданной ширины (LANCZOS), квантует палитру, опционально
выбрасывает каждый второй кадр (длительности соседних кадров складываются — скорость
анимации не меняется). ПРОЗРАЧНЫЙ ФОН СОХРАНЯЕТСЯ (прозрачным пикселям выделяется
отдельный индекс палитры). Результат кладёт рядом: <имя>_<ширина>px.gif.

Клиент мода всё равно ужимает кадры до 512 px перед загрузкой в GPU, поэтому ширина
320–512 px — оптимум для эмоций: меньше трафика без видимой потери качества.

Требуется Pillow: pip install pillow
"""

import os
import sys
import threading
import queue

from PIL import Image, ImageSequence

DEFAULT_WIDTH = 320
DEFAULT_COLORS = 255


def shrink(src_path, width=DEFAULT_WIDTH, colors=DEFAULT_COLORS, drop_half=False, out_path=None):
    """Сжимает один GIF; возвращает (путь результата, размер до, размер после)."""
    src = Image.open(src_path)
    if (src.format or "").upper() != "GIF":
        raise ValueError("не GIF: " + src_path)

    frames = []
    durations = []
    for frame in ImageSequence.Iterator(src):
        rgba = frame.convert("RGBA")
        w, h = rgba.size
        if w > width:
            rgba = rgba.resize((width, int(h * width / w)), Image.LANCZOS)
        frames.append(rgba)
        durations.append(frame.info.get("duration", 100))

    if drop_half and len(frames) > 3:
        # Каждый второй кадр выбрасывается, его время отдаётся предыдущему —
        # анимация идёт с той же скоростью, файл примерно вдвое меньше.
        kept, kept_durations = [], []
        for i, (frame, duration) in enumerate(zip(frames, durations)):
            if i % 2 == 0:
                kept.append(frame)
                kept_durations.append(duration)
            else:
                kept_durations[-1] += duration
        frames, durations = kept, kept_durations

    # Прозрачность: если в исходнике есть прозрачные пиксели, квантуем цвета в 255 индексов
    # (0..254), а индекс 255 резервируем под прозрачность — и помечаем его в GIF. Простое
    # quantize() по RGBA теряет альфу в зависимости от версии Pillow, поэтому явный путь.
    colors_capped = max(2, min(255, colors))
    has_alpha = any(f.getchannel("A").getextrema()[0] < 255 for f in frames)
    quantized = []
    if has_alpha:
        for f in frames:
            mask = f.getchannel("A").point(lambda a: 255 if a < 128 else 0)
            p = f.convert("RGB").quantize(colors=colors_capped, method=Image.FASTOCTREE,
                                          dither=Image.FLOYDSTEINBERG)
            p.paste(255, mask)              # прозрачные пиксели -> зарезервированный индекс
            p.info["transparency"] = 255
            quantized.append(p)
    else:
        quantized = [
            f.convert("RGB").quantize(colors=colors_capped, method=Image.FASTOCTREE,
                                      dither=Image.FLOYDSTEINBERG)
            for f in frames
        ]

    if out_path is None:
        base, _ = os.path.splitext(src_path)
        out_path = base + "_" + str(width) + "px.gif"
    save_kwargs = dict(save_all=True, append_images=quantized[1:],
                       loop=src.info.get("loop", 0), duration=durations,
                       disposal=2, optimize=True)
    if has_alpha:
        save_kwargs["transparency"] = 255
    quantized[0].save(out_path, **save_kwargs)
    return out_path, os.path.getsize(src_path), os.path.getsize(out_path)


def format_size(num_bytes):
    if num_bytes >= 1024 * 1024:
        return "%.1f МБ" % (num_bytes / 1024 / 1024)
    return "%d КБ" % (num_bytes / 1024)


# ---------------------------------------------------------------------------------------------
# Консольный режим (файлы в аргументах — в т.ч. перетаскивание на shrink_gif.bat)
# ---------------------------------------------------------------------------------------------

def run_cli(argv):
    width, colors, drop_half, out_path = DEFAULT_WIDTH, DEFAULT_COLORS, False, None
    files = []
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg in ("-w", "--width"):
            i += 1
            width = int(argv[i])
        elif arg in ("-c", "--colors"):
            i += 1
            colors = int(argv[i])
        elif arg == "--drop-half":
            drop_half = True
        elif arg in ("-o", "--out"):
            i += 1
            out_path = argv[i]
        else:
            files.append(arg)
        i += 1
    if not files:
        print("использование: shrink_gif.py файл.gif [ещё.gif ...] [-w 320] [-c 255] [--drop-half] [-o out.gif]")
        return 2
    code = 0
    for path in files:
        try:
            out, before, after = shrink(path, width, colors, drop_half,
                                        out_path if len(files) == 1 else None)
            print("%s: %s -> %s  (%s)" % (os.path.basename(path), format_size(before),
                                          format_size(after), out))
        except Exception as e:  # noqa: BLE001 — сообщаем и продолжаем со следующим файлом
            print("ОШИБКА %s: %s" % (path, e))
            code = 1
    return code


# ---------------------------------------------------------------------------------------------
# Оконный режим (запуск без аргументов)
# ---------------------------------------------------------------------------------------------

def run_gui():
    import tkinter as tk
    from tkinter import filedialog, ttk

    root = tk.Tk()
    root.title("Сжатие GIF — эмоции")
    root.geometry("560x420")

    top = ttk.Frame(root, padding=8)
    top.pack(fill="x")

    ttk.Label(top, text="Ширина (px):").grid(row=0, column=0, sticky="w")
    width_var = tk.StringVar(value=str(DEFAULT_WIDTH))
    ttk.Combobox(top, textvariable=width_var, values=("256", "320", "384", "512"),
                 width=6).grid(row=0, column=1, padx=(4, 16))

    ttk.Label(top, text="Цветов:").grid(row=0, column=2, sticky="w")
    colors_var = tk.StringVar(value=str(DEFAULT_COLORS))
    ttk.Combobox(top, textvariable=colors_var, values=("64", "128", "192", "255"),
                 width=6).grid(row=0, column=3, padx=(4, 16))

    drop_var = tk.BooleanVar(value=False)
    ttk.Checkbutton(top, text="убрать каждый 2-й кадр (≈вдвое меньше)",
                    variable=drop_var).grid(row=0, column=4, sticky="w")

    log = tk.Text(root, height=16, state="disabled")
    log.pack(fill="both", expand=True, padx=8, pady=(0, 4))

    messages = queue.Queue()

    def println(text):
        messages.put(text)

    def poll_messages():
        try:
            while True:
                line = messages.get_nowait()
                log.configure(state="normal")
                log.insert("end", line + "\n")
                log.see("end")
                log.configure(state="disabled")
        except queue.Empty:
            pass
        root.after(100, poll_messages)

    def worker(paths, width, colors, drop_half):
        for path in paths:
            try:
                println("Сжимаю " + os.path.basename(path) + "…")
                out, before, after = shrink(path, width, colors, drop_half)
                println("  %s -> %s   сохранено: %s" % (format_size(before), format_size(after), out))
            except Exception as e:  # noqa: BLE001 — показываем ошибку в окне
                println("  ОШИБКА: %s" % e)
        println("Готово.")
        button.configure(state="normal")

    def pick_and_run():
        paths = filedialog.askopenfilenames(title="Выбери GIF-файлы",
                                            filetypes=[("GIF", "*.gif")])
        if not paths:
            return
        try:
            width = int(width_var.get())
            colors = int(colors_var.get())
        except ValueError:
            println("Ширина и число цветов должны быть числами.")
            return
        button.configure(state="disabled")
        threading.Thread(target=worker, args=(list(paths), width, colors, drop_var.get()),
                         daemon=True).start()

    button = ttk.Button(root, text="Выбрать GIF и сжать…", command=pick_and_run)
    button.pack(pady=(0, 8))

    println("Выбери GIF — сжатая копия появится рядом с оригиналом (<имя>_<ширина>px.gif).")
    println("Совет: 320–512 px достаточно (клиент всё равно ужимает кадры до 512 px).")
    poll_messages()
    root.mainloop()
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 1:
        sys.exit(run_cli(sys.argv[1:]))
    sys.exit(run_gui())
