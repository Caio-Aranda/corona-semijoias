"""Gera os ícones do Corona Gestão (vinho) e do Corona Vendas (dourado suave), estilo C da proposta visual.
Uso: python scripts/gerar-icones.py   (precisa do Pillow). Saída: apps/<app>/build/icon.ico, icon.png (512) e icone-janela.png."""
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw

RAIZ = Path(__file__).resolve().parent.parent
SIMBOLO = Image.open(RAIZ / "docs/marca/corona_semijoias_simbolo_preto.png").convert("RGBA")
T = 1024  # desenha grande e reduz (suaviza as bordas)

APPS = {
    "owner-app": dict(de=(0x7A, 0x2A, 0x4A), ate=(0x2B, 0x0F, 0x1E), simbolo=(0xDE, 0xB7, 0x5E), borda=(0xDE, 0xB7, 0x5E, 115)),
    # Dourado do Vendas um pouco mais suave que na proposta (#efd290 -> #c08e3b).
    "seller-app": dict(de=(0xF3, 0xE1, 0xB4), ate=(0xD2, 0xA4, 0x58), simbolo=(0x3B, 0x1F, 0x2B), borda=(255, 255, 255, 128)),
}

def gradiente(de, ate):
    g = Image.new("RGBA", (T, T))
    px = g.load()
    for y in range(T):
        for x in range(T):
            t = (x + y) / (2 * (T - 1))
            px[x, y] = tuple(round(de[i] + (ate[i] - de[i]) * t) for i in range(3)) + (255,)
    return g

def icone(cfg):
    margem, raio = int(T * 0.03), int(T * 0.235)
    caixa = (margem, margem, T - margem, T - margem)
    mascara = Image.new("L", (T, T), 0)
    ImageDraw.Draw(mascara).rounded_rectangle(caixa, raio, fill=255)
    fundo = gradiente(cfg["de"], cfg["ate"])
    # brilho suave no alto
    brilho = Image.new("RGBA", (T, T), (255, 255, 255, 0))
    bp = brilho.load()
    for y in range(T // 2):
        a = round(70 * (1 - y / (T / 2)))
        for x in range(T):
            bp[x, y] = (255, 255, 255, a)
    fundo = Image.alpha_composite(fundo, brilho)
    # fio na borda
    fio = Image.new("RGBA", (T, T), (0, 0, 0, 0))
    ImageDraw.Draw(fio).rounded_rectangle(caixa, raio, outline=cfg["borda"], width=8)
    fundo = Image.alpha_composite(fundo, fio)
    fundo.putalpha(mascara)
    # símbolo pintado da cor do app
    lado = int(T * 0.74)
    s = SIMBOLO.resize((lado, lado), Image.LANCZOS)
    cor = Image.new("RGBA", s.size, cfg["simbolo"] + (255,))
    cor.putalpha(ImageChops.multiply(s.getchannel("A"), Image.new("L", s.size, 255)))
    fundo.alpha_composite(cor, ((T - lado) // 2, int(T * 0.145)))
    return fundo

for app, cfg in APPS.items():
    img = icone(cfg)
    saida = RAIZ / "apps" / app / "build"
    img.resize((512, 512), Image.LANCZOS).save(saida / "icon.png")
    img.save(saida / "icon.ico", sizes=[(256, 256), (48, 48), (32, 32), (16, 16)])
    print(app, "ok")
