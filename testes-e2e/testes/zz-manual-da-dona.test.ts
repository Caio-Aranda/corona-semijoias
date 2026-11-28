import { expect, test, type Locator, type Page } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DONA } from '../ambiente';
import { abrirOApp, type AppAberto } from '../apoio';
import { criarMassaDoManual, PECAS_DO_MANUAL, PESSOAS_DO_MANUAL } from '../massa-do-manual';

// Grava o MANUAL e o VÍDEO da dona (Corona Gestão em espanhol), uma tela por capítulo, contra o banco e a API de teste
// isolados do e2e, com dados de exemplo claramente fictícios. Nunca roda contra a produção.
// Só roda com CORONA_MANUAL apontando para uma pasta vazia (fora do git); sem ela, o teste é pulado.
//   Windows (PowerShell): $env:CORONA_MANUAL='C:/Projetos/manual-lucia/gravacao'; npm run e2e -- zz-manual-da-dona
// Saída em CORONA_MANUAL: capturas/*.png (uma por passo), video/*.webm (a gravação inteira) e tiempos.json (o segundo em
// que cada capítulo começa). Os textos de cada passo (legenda do vídeo e linha do manual) vêm de
// testes-e2e/manual-da-dona/capitulos.json; scripts/manual-da-dona/gerar.mjs monta o manual e o arquivo de tempos.
test.describe.configure({ mode: 'serial' });
// Um seletor errado não pode travar a gravação por uma hora: cada ação espera no máximo 15 s.
test.use({ actionTimeout: 15_000 });

const PASTA = process.env['CORONA_MANUAL'];
const aqui = dirname(fileURLToPath(import.meta.url));

interface Capitulo {
  id: string;
  titulo: string;
  pasos: { id: string; texto: string }[];
}
const { capitulos } = JSON.parse(
  readFileSync(resolve(aqui, '..', 'manual-da-dona', 'capitulos.json'), 'utf8'),
) as { capitulos: Capitulo[] };
const textos = new Map(capitulos.flatMap((c) => c.pasos.map((p) => [p.id, p.texto] as const)));
const tituloDe = new Map(capitulos.map((c) => [c.id, c.titulo] as const));

let sessao: AppAberto | undefined;
test.afterEach(async () => {
  // Com a gravação ligada, fechar o app pode demorar (o vídeo é fechado junto): fecha a gravação primeiro e não espera mais
  // de um minuto pelo programa.
  const aberto = sessao;
  sessao = undefined;
  if (!aberto) return;
  const video = aberto.janela.video();
  await aberto.app
    .context()
    .close()
    .catch(() => undefined);
  if (PASTA && video)
    await video.saveAs(join(PASTA, 'video', 'gestion.webm')).catch(() => undefined);
  await Promise.race([
    aberto.app.close().catch(() => undefined),
    new Promise((ok) => setTimeout(ok, 60_000)),
  ]);
  try {
    aberto.app.process().kill();
  } catch {
    // o programa já tinha fechado
  }
});

const tiempos: { id: string; titulo: string; segundo: number }[] = [];
let inicioDoVideo = 0;

/** CORONA_MANUAL_RAPIDO=1: pausas curtas, só para acertar os seletores (o vídeo de verdade roda sem isto). */
const RAPIDO = process.env['CORONA_MANUAL_RAPIDO'] === '1';
async function pausa(janela: Page, ms: number): Promise<void> {
  await janela.waitForTimeout(RAPIDO ? Math.min(ms, 250) : ms);
}

/** A legenda do vídeo: embaixo da tela, texto grande, com o capítulo em cima. */
async function legenda(janela: Page, capitulo: string, texto: string | null): Promise<void> {
  await janela.evaluate(
    ([cap, t]) => {
      let e = document.getElementById('legenda-do-manual');
      if (!t) {
        e?.remove();
        return;
      }
      if (!e) {
        e = document.createElement('div');
        e.id = 'legenda-do-manual';
        e.style.cssText = [
          'position:fixed',
          'left:50%',
          'bottom:26px',
          'transform:translateX(-50%)',
          'width:min(1120px,92vw)',
          'box-sizing:border-box',
          'background:rgba(35,24,18,0.94)',
          'color:#fff',
          'font:600 26px/1.35 Inter,Arial,sans-serif',
          'padding:16px 28px 18px',
          'border-radius:14px',
          'border:2px solid #c9a24d',
          'z-index:2147483647',
          'pointer-events:none',
          'text-align:center',
        ].join(';');
        document.body.appendChild(e);
      }
      e.innerHTML = '';
      const pequeno = document.createElement('div');
      pequeno.style.cssText =
        'font:700 16px Inter,Arial,sans-serif;color:#e8c777;margin-bottom:6px';
      pequeno.textContent = cap;
      const linha = document.createElement('div');
      linha.textContent = t;
      e.append(pequeno, linha);
    },
    [capitulo, texto] as const,
  );
}

async function tituloDoCapitulo(janela: Page, texto: string | null): Promise<void> {
  await janela.evaluate((t) => {
    document.getElementById('titulo-do-capitulo')?.remove();
    if (!t) return;
    const e = document.createElement('div');
    e.id = 'titulo-do-capitulo';
    e.style.cssText = [
      'position:fixed',
      'inset:0',
      'background:#2b1d17',
      'color:#e8c777',
      'font:500 60px/1.2 "Cormorant Garamond",Georgia,serif',
      'display:flex',
      'align-items:center',
      'justify-content:center',
      'text-align:center',
      'padding:60px',
      'z-index:2147483647',
    ].join(';');
    e.textContent = t;
    document.body.appendChild(e);
  }, texto);
}

/** Para a gravação antes do capítulo N (CORONA_MANUAL_ATE=3 grava só os capítulos 1 e 2): útil para acertar um capítulo. */
const PARAR = Symbol('parar');
const ATE = Number(process.env['CORONA_MANUAL_ATE'] ?? '0');

async function capitulo(janela: Page, id: string): Promise<void> {
  if (ATE > 0 && Number(id) >= ATE) throw PARAR;
  const titulo = tituloDe.get(id) ?? id;
  tiempos.push({
    id,
    titulo,
    segundo: Math.max(0, (Date.now() - inicioDoVideo) / 1000 - 0.3),
  });
  await tituloDoCapitulo(janela, `${Number(id)}. ${titulo}`);
  await pausa(janela, 2400);
  await tituloDoCapitulo(janela, null);
  await pausa(janela, 300);
}

async function captura(janela: Page, id: string): Promise<void> {
  if (!PASTA) return;
  const pasta = join(PASTA, 'capturas');
  mkdirSync(pasta, { recursive: true });
  await legenda(janela, '', null);
  // Um botão que acabou de ser tocado não deve sair na foto com o contorno de foco (os campos de texto, sim: é o que a dona vê).
  await janela.evaluate(() => {
    const ativo = document.activeElement as HTMLElement | null;
    if (ativo && !['INPUT', 'SELECT', 'TEXTAREA'].includes(ativo.tagName)) ativo.blur();
  });
  await pausa(janela, 700);
  await janela.screenshot({ path: join(pasta, `${id}.png`) });
}

/**
 * O contorno rosa é desenhado por cima da tela (caixas soltas, sem mexer no estilo dos botões do programa): assim não sobra
 * contorno preso nos botões e o programa não muda em nada.
 */
async function resaltar(alvos: Locator[], ligar: boolean): Promise<void> {
  const janela = alvos[0]?.page();
  if (!janela) return;
  if (!ligar) {
    await janela.evaluate(() => document.getElementById('resaltos-del-manual')?.remove());
    return;
  }
  const caixas: { x: number; y: number; width: number; height: number }[] = [];
  for (const a of alvos) {
    const caixa = await a
      .first()
      .boundingBox({ timeout: 3000 })
      .catch(() => null);
    if (caixa) caixas.push(caixa);
  }
  await janela.evaluate((lista) => {
    document.getElementById('resaltos-del-manual')?.remove();
    const raiz = document.createElement('div');
    raiz.id = 'resaltos-del-manual';
    raiz.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483646';
    for (const c of lista) {
      const e = document.createElement('div');
      // Pelo estilo do próprio elemento (CSSOM): o programa não aceita estilo escrito em atributo (política de segurança).
      e.style.cssText = `position:fixed;left:${c.x - 6}px;top:${c.y - 6}px;width:${c.width + 12}px;height:${c.height + 12}px;border:5px solid #d6336c;border-radius:12px;box-sizing:border-box`;
      raiz.appendChild(e);
    }
    document.body.appendChild(raiz);
  }, caixas);
}

interface OpcoesDoPaso {
  /** Marca com um contorno rosa o que a dona vai tocar ou ler (aparece na captura e no vídeo). */
  resaltar?: Locator | Locator[];
  /** O que a dona escreve ou escolhe antes de tocar (roda com o contorno já na tela, antes da captura "antes"). */
  preparar?: () => Promise<void>;
  /** O que a dona faz neste passo. */
  accion?: () => Promise<void>;
  /**
   * Quando tirar a captura. "antes" (padrão se há contorno): mostra o botão que vai tocar. "despues": mostra o resultado
   * (o contorno fica na tela até a captura).
   */
  foto?: 'antes' | 'despues';
}

/**
 * Um passo do manual: legenda na tela, contorno no que se toca, captura, ação. A legenda fica o tempo de ler.
 */
async function paso(janela: Page, id: string, o: OpcoesDoPaso = {}): Promise<void> {
  try {
    await pasoSemFalha(janela, id, o);
  } catch (erro) {
    // A foto da tela no momento da falha ajuda a achar o botão que mudou de nome.
    if (PASTA) {
      mkdirSync(join(PASTA, 'falhas'), { recursive: true });
      await janela.screenshot({ path: join(PASTA, 'falhas', `${id}.png`) }).catch(() => undefined);
    }
    throw erro;
  }
}

async function pasoSemFalha(janela: Page, id: string, o: OpcoesDoPaso): Promise<void> {
  const texto = textos.get(id);
  if (!texto) throw new Error(`Passo sem texto em capitulos.json: ${id}`);
  const cap = `${Number(id.slice(0, 2))}. ${tituloDe.get(id.slice(0, 2)) ?? ''}`;
  const alvos = o.resaltar ? (Array.isArray(o.resaltar) ? o.resaltar : [o.resaltar]) : [];
  const foto = o.foto ?? (alvos.length > 0 ? 'antes' : 'despues');
  const leitura = Math.max(4200, texto.length * 62);

  await legenda(janela, cap, texto);
  if (o.preparar) {
    await pausa(janela, 900);
    await o.preparar();
    await pausa(janela, 700);
  }
  if (alvos.length > 0) {
    await alvos[0]!.first().scrollIntoViewIfNeeded();
    await resaltar(alvos, true);
  }
  await pausa(janela, 900);
  if (foto === 'antes') {
    await captura(janela, id);
    await legenda(janela, cap, texto);
    await pausa(janela, leitura - 900);
    await resaltar(alvos, false);
    if (o.accion) await o.accion();
    await pausa(janela, 1100);
    return;
  }
  if (o.accion) await o.accion();
  await pausa(janela, 1100);
  await captura(janela, id);
  await resaltar(alvos, false);
  await legenda(janela, cap, texto);
  await pausa(janela, Math.max(1800, leitura - 2000));
}

async function menu(janela: Page, item: string): Promise<void> {
  await botonDelMenu(janela, item).click();
  await pausa(janela, 900);
}

function botonDelMenu(janela: Page, item: string): Locator {
  return janela
    .getByRole('navigation', { name: 'Menú de Corona Gestión' })
    .getByRole('button', { name: item, exact: true })
    .first(); // o grupo Informes, no fim do menu, repete os nomes (Stock, Maletas...): vale o primeiro, o do menu de trabalho
}

async function leerPorExpresion(janela: Page, rotulo: RegExp, codigo: string): Promise<void> {
  const campo = janela.getByLabel(rotulo).first();
  await campo.click();
  await campo.pressSequentially(codigo, { delay: 35 });
  await campo.press('Enter');
}

async function leer(janela: Page, rotulo: string, codigo: string): Promise<void> {
  const campo = janela.getByLabel(rotulo).first();
  await campo.click();
  await campo.pressSequentially(codigo, { delay: 35 });
  await campo.press('Enter');
}

test('manual y video de la dueña: todas las pantallas, en español', async () => {
  test.skip(
    !PASTA,
    'Defina CORONA_MANUAL (una pasta fuera del git) para grabar el manual y el video.',
  );
  test.setTimeout(3_600_000);
  const pasta = PASTA as string;
  mkdirSync(join(pasta, 'video'), { recursive: true });

  const massa = await criarMassaDoManual();
  const { dona } = PESSOAS_DO_MANUAL;
  const MARIA = 'María Ejemplo';
  const senhaNova = 'Mi-Clave-2026';

  sessao = await abrirOApp('es', { gravarVideoEm: join(pasta, 'video') });
  const { janela } = sessao;
  await janela.setViewportSize({ width: 1366, height: 768 });
  // A janela precisa ter o foco de verdade: sem ele o Chromium deixa o contorno de foco preso nos botões que já foram tocados.
  await sessao.app.evaluate(({ BrowserWindow }) => {
    const w = BrowserWindow.getAllWindows()[0];
    w?.show();
    w?.focus();
  });
  inicioDoVideo = Date.now();
  try {
    // 1. Primera entrada
    await capitulo(janela, '01');
    await paso(janela, '01-1', { foto: 'despues' });
    await paso(janela, '01-2', {
      resaltar: [janela.getByLabel('Usuario'), janela.getByLabel('Contraseña', { exact: true })],
      preparar: async () => {
        await janela.getByLabel('Usuario').fill(dona.login);
        await janela.getByLabel('Contraseña', { exact: true }).fill(massa.senhaTemporariaDaDona);
      },
      accion: async () => {
        await janela.getByRole('button', { name: 'Entrar' }).click();
      },
    });
    await expect(janela.getByRole('heading', { name: /Cree su contraseña nueva/ })).toBeVisible();
    await paso(janela, '01-3', { foto: 'despues' });
    await paso(janela, '01-4', {
      resaltar: [
        janela.getByLabel('Contraseña nueva', { exact: true }),
        janela.getByLabel('Repita la contraseña nueva'),
      ],
      preparar: async () => {
        await janela.getByLabel('Contraseña nueva', { exact: true }).fill(senhaNova);
        await janela.getByLabel('Repita la contraseña nueva').fill(senhaNova);
      },
      accion: async () => {
        await janela.getByRole('button', { name: 'Guardar contraseña nueva' }).click();
      },
    });
    await expect(janela.getByRole('button', { name: 'Salir' })).toBeVisible();
    await paso(janela, '01-5', { foto: 'despues' });
    await paso(janela, '01-6', {
      resaltar: janela.getByRole('button', { name: 'Salir' }),
      accion: async () => {
        await janela.getByRole('button', { name: 'Salir' }).click();
      },
    });
    await janela.getByLabel('Usuario').fill(dona.login);
    await janela.getByLabel('Contraseña', { exact: true }).fill(senhaNova);
    await janela.getByRole('button', { name: 'Entrar' }).click();
    await expect(janela.getByRole('button', { name: 'Salir' })).toBeVisible();
    await pausa(janela, 800);

    // 2. El lector de código de barras (se lee en Recibir compra, sin confirmar la compra)
    await capitulo(janela, '02');
    await paso(janela, '02-1', {
      resaltar: botonDelMenu(janela, 'Recibir compra'),
      accion: () => menu(janela, 'Recibir compra'),
    });
    await paso(janela, '02-2', {
      resaltar: janela.getByLabel('Código de la pieza o nombre'),
      accion: async () => {
        await janela
          .getByLabel('Proveedor', { exact: true })
          .selectOption({ label: PESSOAS_DO_MANUAL.proveedor });
        await janela.getByLabel('Código de la pieza o nombre').click();
      },
    });
    await paso(janela, '02-3', {
      foto: 'despues',
      accion: () => leer(janela, 'Código de la pieza o nombre', PECAS_DO_MANUAL[0].codigo),
    });
    await expect(janela.getByText(`${PECAS_DO_MANUAL[0].nome} entró en la compra.`)).toBeVisible();
    await paso(janela, '02-4', {
      foto: 'despues',
      accion: () =>
        leer(janela, 'Código de la pieza o nombre', PECAS_DO_MANUAL[0].codigo.toLowerCase()),
    });
    await paso(janela, '02-5', {
      foto: 'despues',
      accion: () => leer(janela, 'Código de la pieza o nombre', 'XX-999'),
    });
    await expect(
      janela.getByText(/No encontramos ninguna pieza con el código XX-999/),
    ).toBeVisible();
    await paso(janela, '02-6', {
      resaltar: janela.getByRole('button', { name: 'Registrar esta pieza ahora' }),
      accion: async () => {
        // Esta compra era solo para practicar la lectura: se descarta para poder salir de la pantalla sin guardar nada.
        await janela.getByRole('button', { name: 'Empezar de nuevo' }).click();
        const aviso = janela.getByRole('dialog');
        if (await aviso.isVisible().catch(() => false)) {
          await aviso
            .getByRole('button', { name: /^(Empezar de nuevo|Sí|Salir sin guardar|Confirmar)/ })
            .first()
            .click();
        }
      },
    });

    // 3. Registrar la semana
    await capitulo(janela, '03');
    await paso(janela, '03-1', {
      resaltar: botonDelMenu(janela, 'Semanas'),
      accion: () => menu(janela, 'Semanas'),
    });
    await paso(janela, '03-2', {
      resaltar: janela.getByRole('button', { name: 'Nueva semana' }),
      accion: async () => {
        await janela.getByRole('button', { name: 'Nueva semana' }).click();
      },
    });
    const semanaNova = PESSOAS_DO_MANUAL.semanaNova;
    await paso(janela, '03-3', {
      foto: 'despues',
      resaltar: [
        janela.getByLabel('Número de la semana'),
        janela.getByLabel('Primer día'),
        janela.getByLabel('Último día'),
      ],
      preparar: async () => {
        await janela.getByLabel('Número de la semana').fill(String(semanaNova.numero));
        await janela.getByLabel('Primer día').fill(semanaNova.inicio);
        await janela.getByLabel('Último día').fill(semanaNova.fim);
      },
    });
    const botonGuardar = janela.getByRole('button', { name: /^(Guardar|Registrar)/ }).last();
    await paso(janela, '03-4', {
      foto: 'despues',
      resaltar: botonGuardar,
      accion: async () => {
        await botonGuardar.click();
        await expect(janela.getByText(`Semana ${semanaNova.numero} fue registrada.`)).toBeVisible();
      },
    });

    // 4. Registrar un representante con límite
    await capitulo(janela, '04');
    await paso(janela, '04-1', {
      resaltar: botonDelMenu(janela, 'Representantes'),
      accion: () => menu(janela, 'Representantes'),
    });
    await paso(janela, '04-2', {
      resaltar: janela.getByRole('button', { name: 'Nuevo representante' }),
      accion: async () => {
        await janela.getByRole('button', { name: 'Nuevo representante' }).click();
      },
    });
    await paso(janela, '04-3', {
      foto: 'despues',
      resaltar: janela.getByLabel('Nombre del representante'),
      preparar: async () => {
        await janela.getByLabel('Nombre del representante').fill(MARIA);
      },
    });
    await paso(janela, '04-4', {
      foto: 'despues',
      resaltar: janela.getByLabel('Límite de consignación'),
      preparar: async () => {
        await janela.getByLabel('Límite de consignación').fill('1500,00');
      },
    });
    await paso(janela, '04-5', {
      foto: 'despues',
      resaltar: janela.getByRole('button', { name: 'Guardar', exact: true }),
      accion: async () => {
        await janela.getByRole('button', { name: 'Guardar', exact: true }).click();
        await expect(janela.getByText(/fue registrad/)).toBeVisible();
      },
    });

    // 5. Armar y enviar una maleta
    await capitulo(janela, '05');
    await paso(janela, '05-1', {
      resaltar: botonDelMenu(janela, 'Armar maleta'),
      accion: () => menu(janela, 'Armar maleta'),
    });
    await paso(janela, '05-2', {
      foto: 'despues',
      resaltar: [janela.getByLabel('Representante'), janela.getByLabel('Semana')],
      preparar: async () => {
        await janela.getByLabel('Representante').selectOption({ label: MARIA });
        await janela.getByLabel('Semana').selectOption({ label: `Semana ${semanaNova.numero}` });
      },
      accion: async () => {
        await janela.getByRole('button', { name: 'Empezar a armar' }).click();
        await expect(janela.getByLabel('Código de la pieza o nombre')).toBeVisible();
      },
    });
    const [pendientes, collar, pulsera, anillo] = PECAS_DO_MANUAL;
    const campoDeCodigo = 'Código de la pieza o nombre';
    await paso(janela, '05-3', {
      foto: 'despues',
      accion: async () => {
        await leer(janela, campoDeCodigo, pendientes.codigo);
        await leer(janela, campoDeCodigo, pendientes.codigo);
        await leer(janela, campoDeCodigo, collar.codigo);
        await expect(janela.getByText(`${collar.nome} entró en la maleta.`)).toBeVisible();
      },
    });
    await paso(janela, '05-4', {
      foto: 'despues',
      resaltar: janela.getByText('Límite del representante'),
    });
    await paso(janela, '05-5', {
      foto: 'despues',
      accion: async () => {
        await leer(janela, campoDeCodigo, anillo.codigo);
        await expect(janela.getByText(`${anillo.nome} está sin stock`)).toBeVisible();
      },
    });
    const actualizarPendientes = janela.getByRole('button', {
      name: `Actualizar la cantidad de ${pendientes.nome}`,
    });
    await paso(janela, '05-6', {
      foto: 'despues',
      resaltar: janela.getByText(/pasa el límite/),
      preparar: async () => {
        await janela.getByLabel(`Cantidad de ${pendientes.nome}`).fill('18');
        await actualizarPendientes.click();
        await expect(janela.getByText(/pasa el límite/)).toBeVisible();
      },
    });
    await paso(janela, '05-7', {
      resaltar: actualizarPendientes,
      preparar: async () => {
        await janela.getByLabel(`Cantidad de ${pendientes.nome}`).fill('2');
      },
      accion: async () => {
        await actualizarPendientes.click();
        await expect(janela.getByText(/pasa el límite/)).toBeHidden();
      },
    });
    await paso(janela, '05-8', {
      resaltar: janela.getByRole('button', { name: 'Enviar maleta' }),
      accion: async () => {
        await janela.getByRole('button', { name: 'Enviar maleta' }).click();
      },
    });
    await paso(janela, '05-9', {
      resaltar: janela.getByRole('dialog').getByRole('button', { name: 'Enviar maleta' }),
      accion: async () => {
        await janela.getByRole('dialog').getByRole('button', { name: 'Enviar maleta' }).click();
        await expect(janela.getByText('Maleta enviada', { exact: true }).first()).toBeVisible();
      },
    });
    await paso(janela, '05-10', {
      foto: 'despues',
      resaltar: janela.getByRole('button', { name: 'Imprimir nota' }),
    });

    // 6. Reponer una maleta
    await capitulo(janela, '06');
    await paso(janela, '06-1', {
      foto: 'despues',
      resaltar: botonDelMenu(janela, 'Maletas'),
      accion: async () => {
        await menu(janela, 'Maletas');
        await janela.getByRole('button', { name: 'Por representante', exact: true }).click();
      },
    });
    const reponer = janela.getByRole('button', { name: `Nueva reposición para ${MARIA}` });
    await paso(janela, '06-2', {
      resaltar: reponer,
      accion: async () => {
        await reponer.click();
      },
    });
    await paso(janela, '06-3', {
      foto: 'despues',
      resaltar: janela.getByLabel('Semana'),
      preparar: async () => {
        await janela.getByLabel('Semana').selectOption({ label: `Semana ${semanaNova.numero}` });
      },
      accion: async () => {
        await janela.getByRole('button', { name: 'Empezar a reponer' }).click();
      },
    });
    await paso(janela, '06-4', {
      foto: 'despues',
      accion: async () => {
        await leer(janela, campoDeCodigo, pendientes.codigo);
        await expect(janela.getByText(`${pendientes.nome} entró en la maleta.`)).toBeVisible();
        await janela.getByRole('button', { name: 'Enviar maleta' }).click();
        await janela.getByRole('dialog').getByRole('button', { name: 'Enviar maleta' }).click();
        await expect(janela.getByText('Maleta enviada', { exact: true }).first()).toBeVisible();
      },
    });

    // 7. Ver dónde está cada pieza
    await capitulo(janela, '07');
    await paso(janela, '07-1', {
      resaltar: botonDelMenu(janela, 'Stock'),
      accion: () => menu(janela, 'Stock'),
    });
    await paso(janela, '07-2', {
      foto: 'despues',
      resaltar: janela.getByLabel('Nombre, referencia o código'),
      preparar: async () => {
        await janela.getByLabel('Nombre, referencia o código').fill('Pulsera');
      },
      accion: async () => {
        await janela.getByRole('button', { name: 'Buscar', exact: true }).click();
        await expect(janela.getByText(/Con los representantes/).first()).toBeVisible();
      },
    });
    await paso(janela, '07-3', {
      foto: 'despues',
      resaltar: janela.getByText(/Con los representantes/).first(),
    });

    // 8. Cierre de la maleta (la maleta de Ana: una nota acertada y otra todavía con la cliente)
    await capitulo(janela, '08');
    await paso(janela, '08-1', {
      resaltar: botonDelMenu(janela, 'Cierre de la maleta'),
      accion: () => menu(janela, 'Cierre de la maleta'),
    });
    await paso(janela, '08-2', {
      foto: 'despues',
      resaltar: janela.getByLabel('Maleta que volvió'),
      preparar: async () => {
        const opcion = janela
          .getByLabel('Maleta que volvió')
          .locator('option')
          .filter({ hasText: 'Ana Ejemplo' });
        await janela
          .getByLabel('Maleta que volvió')
          .selectOption({ label: (await opcion.first().textContent()) ?? '' });
      },
      accion: async () => {
        await janela.getByRole('button', { name: 'Abrir el cierre' }).click();
      },
    });
    await paso(janela, '08-3', {
      resaltar: janela.getByRole('button', { name: 'Empezar el cierre' }),
      accion: async () => {
        await janela.getByRole('button', { name: 'Empezar el cierre' }).click();
      },
    });
    await paso(janela, '08-4', {
      foto: 'despues',
      accion: async () => {
        await leerPorExpresion(janela, /código/i, collar.codigo);
        for (let i = 0; i < 5; i++) await leerPorExpresion(janela, /código/i, pulsera.codigo);
      },
    });
    await paso(janela, '08-5', {
      foto: 'despues',
      resaltar: janela.getByText(/Faltan/).first(),
    });
    await paso(janela, '08-6', {
      resaltar: janela.getByRole('button', { name: 'Concluir el cierre' }),
      accion: async () => {
        await janela.getByRole('button', { name: 'Concluir el cierre' }).click();
      },
    });
    await paso(janela, '08-7', {
      resaltar: janela.getByText('Qué hacer con cada pieza que falta'),
      preparar: async () => {
        await janela.getByRole('radio', { name: 'Pérdida de la tienda' }).first().check();
        await janela.getByLabel('Motivo de la pérdida').fill('Se rompió');
        await janela.getByRole('radio', { name: 'Cobrar al representante' }).last().check();
      },
      accion: async () => {
        await janela.getByRole('button', { name: 'Concluir el cierre' }).click();
        await janela
          .getByRole('dialog')
          .getByRole('button', { name: 'Concluir', exact: true })
          .click();
        await expect(janela.getByText('Faltantes registrados en el cierre')).toBeVisible();
      },
    });
    await paso(janela, '08-8', {
      foto: 'despues',
      resaltar: janela.getByText(/A cobrar a /).first(),
    });
    await paso(janela, '08-9', {
      resaltar: janela.getByRole('button', { name: /^Registrar pago de / }).first(),
      accion: async () => {
        await janela
          .getByRole('button', { name: /^Registrar pago de / })
          .first()
          .click();
        await janela.getByLabel('Valor pagado').fill('50,00');
        await pausa(janela, 1200);
        await janela.getByRole('button', { name: 'Registrar', exact: true }).click();
        await expect(janela.getByText('Pago registrado. Entró en la caja.')).toBeVisible();
      },
    });
    await paso(janela, '08-10', {
      foto: 'despues',
      resaltar: janela.getByLabel(campoDeCodigo),
      accion: async () => {
        await leer(janela, campoDeCodigo, pulsera.codigo);
        await expect(janela.getByText(/todavía está con el cliente/)).toBeVisible();
      },
    });

    // 9. Acerto de la nota y comisión por la meta
    await capitulo(janela, '09');
    await paso(janela, '09-1', {
      foto: 'despues',
      resaltar: botonDelMenu(janela, 'Notas de los clientes'),
      accion: () => menu(janela, 'Notas de los clientes'),
    });
    const notaRosa = janela.getByRole('row').filter({ hasText: PESSOAS_DO_MANUAL.clienteRosa });
    await paso(janela, '09-2', {
      resaltar: notaRosa.getByRole('button', { name: /^Ver la nota/ }),
      accion: async () => {
        await notaRosa.getByRole('button', { name: /^Ver la nota/ }).click();
      },
    });
    await paso(janela, '09-3', {
      foto: 'despues',
      resaltar: [
        janela.getByText('Valor VD en Guaraníes'),
        janela.getByText('Resultado de la meta'),
      ],
    });
    await paso(janela, '09-4', {
      foto: 'despues',
      resaltar: janela.getByText('Total a cobrar').first(),
    });
    await paso(janela, '09-5', {
      foto: 'despues',
      resaltar: botonDelMenu(janela, 'Comisión y meta del consignado'),
      accion: () => menu(janela, 'Comisión y meta del consignado'),
    });

    // 10. Recibir una compra (el anillo, que estaba sin stock)
    await capitulo(janela, '10');
    await paso(janela, '10-1', {
      foto: 'despues',
      resaltar: botonDelMenu(janela, 'Recibir compra'),
      accion: async () => {
        await menu(janela, 'Recibir compra');
        await janela
          .getByLabel('Proveedor', { exact: true })
          .selectOption({ label: PESSOAS_DO_MANUAL.proveedor });
      },
    });
    await paso(janela, '10-2', {
      foto: 'despues',
      accion: () => leer(janela, campoDeCodigo, anillo.codigo),
    });
    await paso(janela, '10-3', {
      foto: 'despues',
      resaltar: [
        janela.getByLabel(`Cantidad de ${anillo.nome}`),
        janela.getByLabel(`Costo de cada pieza de ${anillo.nome}`),
      ],
      preparar: async () => {
        await janela.getByLabel(`Cantidad de ${anillo.nome}`).fill('12');
      },
    });
    await paso(janela, '10-4', {
      resaltar: janela.getByRole('button', { name: 'Confirmar compra' }),
      accion: async () => {
        await janela.getByRole('button', { name: 'Confirmar compra' }).click();
        await janela.getByRole('dialog').getByRole('button', { name: 'Confirmar compra' }).click();
        await expect(janela.getByText('Compra guardada.')).toBeVisible();
      },
    });
    await paso(janela, '10-5', { foto: 'despues' });

    // 11. Cotización del día
    await capitulo(janela, '11');
    await paso(janela, '11-1', {
      foto: 'despues',
      resaltar: botonDelMenu(janela, 'Inicio'),
      accion: () => menu(janela, 'Inicio'),
    });
    await paso(janela, '11-2', {
      resaltar: janela.getByRole('button', { name: 'Cambiar la cotización' }),
      accion: async () => {
        await janela.getByRole('button', { name: 'Cambiar la cotización' }).click();
      },
    });
    await paso(janela, '11-3', {
      foto: 'despues',
      resaltar: [
        janela.getByLabel('1 dólar = R$'),
        janela.getByLabel('1 real = Gs.'),
        janela.getByRole('button', { name: 'Guardar la cotización' }),
      ],
    });

    // 12. Informes
    await capitulo(janela, '12');
    await menu(janela, 'Inicio');
    await paso(janela, '12-1', {
      resaltar: janela.getByRole('button', { name: /^Informes/ }),
      accion: async () => {
        await janela.getByRole('button', { name: /^Informes/ }).click();
      },
    });
    const grupoDeRegistros = janela.getByRole('button', { name: /^Registros/ }).last();
    await paso(janela, '12-2', {
      resaltar: grupoDeRegistros,
      accion: async () => {
        await grupoDeRegistros.click();
      },
    });
    const informeDeProductos = janela.getByRole('button', { name: /^Productos/ }).last();
    await paso(janela, '12-3', {
      resaltar: informeDeProductos,
      accion: async () => {
        await informeDeProductos.click();
      },
    });
    await paso(janela, '12-4', {
      foto: 'despues',
      resaltar: [
        janela.getByRole('button', { name: 'Descargar en PDF' }),
        janela.getByRole('button', { name: 'Descargar en CSV' }),
      ],
    });

    // 13. Verificación del dinero
    await capitulo(janela, '13');
    await paso(janela, '13-1', {
      foto: 'despues',
      resaltar: botonDelMenu(janela, 'Verificaciones'),
      accion: async () => {
        await menu(janela, 'Verificaciones');
        await janela.getByRole('button', { name: /^Verificación del dinero/ }).click();
        await expect(janela.getByRole('button', { name: 'Verificar ahora' })).toBeVisible();
      },
    });
    await paso(janela, '13-2', {
      resaltar: janela.getByRole('button', { name: 'Verificar ahora' }),
      accion: async () => {
        await janela.getByRole('button', { name: 'Verificar ahora' }).click();
      },
    });
    await paso(janela, '13-3', { foto: 'despues' });

    // 14. Avisos y errores comunes
    await capitulo(janela, '14');
    await menu(janela, 'Inicio');
    await paso(janela, '14-1', {
      foto: 'despues',
      resaltar: janela.getByText('Lo que necesita su atención'),
    });
    await pausa(janela, 1500);
  } catch (erro) {
    if (erro !== PARAR) throw erro;
  }
  // Los tiempos de cada capítulo (el video se guarda solo al cerrar la ventana, en el afterEach).
  writeFileSync(join(pasta, 'tiempos.json'), JSON.stringify(tiempos, null, 2));
});
