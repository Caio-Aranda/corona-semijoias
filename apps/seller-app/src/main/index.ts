import {
  instalarCertificadoFixado,
  PARTICAO_DO_ATUALIZADOR,
  ServicoDeAtualizacao,
} from '@corona/conexao-segura';
import { app, BrowserWindow, net, safeStorage, session, type WebContents } from 'electron';
import electronUpdater from 'electron-updater';
import { join } from 'node:path';
import {
  CANAIS,
  type AvisoDeAtualizacao,
  type AvisoDeSessao,
  type EstadoDaSincronizacao,
} from '../shared/ponte';
import {
  enderecoDaApi,
  exigirImpressoesFora,
  impressoesDoServidor,
  versaoDoApp,
} from './configuracao';
import {
  registrarAtualizacao,
  registrarIdioma,
  registrarPonte,
  registrarSincronizacao,
} from './ipc';
import { montarCoronaVendas } from './montagem';
import { enderecoDoApp } from './seguranca';
import { PreparoDaAtualizacao, prepararAntesDeInstalar } from './sync/preparo-da-atualizacao';
import type { Cofre, Relogio } from './tipos';

const NOME_DO_APP = 'Corona Vendas';
const enderecoDeDesenvolvimento = process.env['ELECTRON_RENDERER_URL'];
/** A única tela empacotada: a janela só carrega e só aceita chamadas deste arquivo (auditoria B1). */
const arquivoDoApp = join(__dirname, '../renderer/index.html');

app.setName(NOME_DO_APP);

let janela: BrowserWindow | null = null;

const cofre: Cofre = {
  disponivel: () => safeStorage.isAsyncEncryptionAvailable(),
  cifrar: async (texto) => new Uint8Array(await safeStorage.encryptStringAsync(texto)),
  // Ao renovar, o token é gravado de novo, então "shouldReEncrypt" já fica atendido.
  decifrar: async (dados) => (await safeStorage.decryptStringAsync(Buffer.from(dados))).result,
};

const relogio: Relogio = {
  agora: () => Date.now(),
  agendar(ms, fn) {
    const id = setTimeout(fn, ms);
    return () => clearTimeout(id);
  },
};

function avisarATela(aviso: AvisoDeSessao): void {
  if (janela && !janela.isDestroyed()) janela.webContents.send(CANAIS.sessaoMudou, aviso);
}

function avisarSincronizacao(estado: EstadoDaSincronizacao): void {
  if (janela && !janela.isDestroyed()) janela.webContents.send(CANAIS.sincronizacaoMudou, estado);
}

/** `net.isOnline()` é só uma dica (ADR-0013, item 19): quando passa a dizer "sim", o motor confere pela rota de saúde. */
function vigiarAInternet(aoVoltar: () => void): () => void {
  let antes = net.isOnline();
  const id = setInterval(() => {
    const agora = net.isOnline();
    if (agora && !antes) aoVoltar();
    antes = agora;
  }, 5_000);
  return () => clearInterval(id);
}

function avisarAtualizacao(aviso: AvisoDeAtualizacao): void {
  if (janela && !janela.isDestroyed()) janela.webContents.send(CANAIS.atualizacaoMudou, aviso);
}

/** A janela só carrega a tela do próprio aplicativo e não abre nada além dela. */
function travarNavegacao(conteudo: WebContents): void {
  conteudo.setWindowOpenHandler(() => ({ action: 'deny' }));
  conteudo.on('will-navigate', (evento, url) => {
    if (!enderecoDoApp(url, enderecoDeDesenvolvimento, arquivoDoApp)) evento.preventDefault();
  });
  conteudo.on('will-attach-webview', (evento) => evento.preventDefault());
}

function criarJanela(): void {
  janela = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    show: false,
    title: NOME_DO_APP,
    backgroundColor: '#FAF7F2',
    // Instalado, o Windows usa o ícone do .exe; no modo de desenvolvimento, o PNG da pasta build.
    ...(app.isPackaged ? {} : { icon: join(app.getAppPath(), 'build', 'icon.png') }),
    webPreferences: {
      preload: join(__dirname, '../preload/index.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      webviewTag: false,
      allowRunningInsecureContent: false,
      spellcheck: false,
    },
  });
  travarNavegacao(janela.webContents);
  janela.once('ready-to-show', () => janela?.show());
  janela.on('closed', () => {
    janela = null;
  });

  if (enderecoDeDesenvolvimento) void janela.loadURL(enderecoDeDesenvolvimento);
  else void janela.loadFile(arquivoDoApp);
}

async function iniciar(): Promise<void> {
  // Nenhuma permissão do navegador é concedida (microfone, localização...), EXCETO a câmera (só imagem, nunca som) para a
  // foto do cliente e do documento no cadastro (tarefa 9.2; §23.5).
  session.defaultSession.setPermissionRequestHandler((_conteudo, permissao, responder, detalhes) => {
    const tipos = (detalhes as { mediaTypes?: readonly string[] }).mediaTypes ?? [];
    responder(permissao === 'media' && tipos.length > 0 && tipos.every((t) => t === 'video'));
  });
  session.defaultSession.setPermissionCheckHandler((_conteudo, permissao, _origem, detalhes) => {
    return permissao === 'media' && (detalhes as { mediaType?: string }).mediaType === 'video';
  });

  // Servidor sem domínio: o HTTPS usa um certificado autoassinado e o app só confia na chave dele (ADR-0009).
  // Sem impressão digital, fora deste computador, o app não conecta. A verificação nunca é desligada.
  const enderecoDoServidor = enderecoDaApi(import.meta.env.MAIN_VITE_CORONA_API_URL);
  const impressoes = impressoesDoServidor(import.meta.env.MAIN_VITE_CORONA_API_PINS);
  exigirImpressoesFora(enderecoDoServidor, impressoes);
  if (impressoes.length > 0) {
    const servidor = { endereco: enderecoDoServidor, impressoes };
    instalarCertificadoFixado(session.defaultSession, servidor);
    // A atualização automática baixa por outra sessão do Chromium; a mesma regra vale nela.
    instalarCertificadoFixado(
      session.fromPartition(PARTICAO_DO_ATUALIZADOR, { cache: false }),
      servidor,
    );
  }
  // A montagem do processo principal mora em `montagem.ts`, sem Electron (os testes de duas pontas usam a mesma).
  const {
    servico,
    motor: motorDoApp,
    bancoLocal,
    batida,
    fechar,
  } = await montarCoronaVendas({
    pastaDeDados: app.getPath('userData'),
    enderecoDoServidor,
    versaoDoApp: versaoDoApp(app.getVersion()),
    cofre,
    // Rede do Chromium: é ela que consulta o verificador de certificado acima.
    fetch: (entrada, inicio) => session.defaultSession.fetch(entrada as string | Request, inicio),
    relogio,
    avisarATela,
    avisarSincronizacao,
    registrar: (mensagem) => console.warn(mensagem),
  });
  const pararDeVigiar = vigiarAInternet(() => motorDoApp.internetVoltou());
  const relogioDaBatida = setInterval(batida, 60_000);
  app.on('will-quit', () => {
    clearInterval(relogioDaBatida);
    pararDeVigiar();
    fechar();
  });

  registrarPonte(servico, (e) => {
    const quadro = e.senderFrame;
    return (
      quadro !== null &&
      janela !== null &&
      e.sender === janela.webContents &&
      enderecoDoApp(quadro.url, enderecoDeDesenvolvimento, arquivoDoApp)
    );
  });

  registrarIdioma(app.getPath('userData'), (e) => {
    const quadro = e.senderFrame;
    return (
      quadro !== null &&
      janela !== null &&
      e.sender === janela.webContents &&
      enderecoDoApp(quadro.url, enderecoDeDesenvolvimento, arquivoDoApp)
    );
  });

  registrarSincronizacao(motorDoApp, (e) => {
    const quadro = e.senderFrame;
    return (
      quadro !== null &&
      janela !== null &&
      e.sender === janela.webContents &&
      enderecoDoApp(quadro.url, enderecoDeDesenvolvimento, arquivoDoApp)
    );
  });

  // Atualização automática (canal próprio do Corona Vendas: /atualizacoes/vendas/), só no app instalado.
  const atualizacao = new ServicoDeAtualizacao({
    atualizador: electronUpdater.autoUpdater,
    endereco: enderecoDoServidor,
    pasta: 'atualizacoes/vendas',
    relogio,
    aoMudar: avisarAtualizacao,
    registrar: (mensagem) => console.warn(mensagem),
  });
  registrarAtualizacao(atualizacao, (e) => {
    const quadro = e.senderFrame;
    return (
      quadro !== null &&
      janela !== null &&
      e.sender === janela.webContents &&
      enderecoDoApp(quadro.url, enderecoDeDesenvolvimento, arquivoDoApp)
    );
  });
  if (app.isPackaged) atualizacao.iniciar();
  // Antes de instalar a versão nova (tarefa 5.13): para o motor, espera o envio (30 s), tenta mais um e fecha o banco.
  // As vendas aguardando atravessam a atualização no próprio banco.
  prepararAntesDeInstalar(
    app,
    new PreparoDaAtualizacao({
      motor: motorDoApp,
      fecharBanco: () => bancoLocal.fechar(),
      relogio,
      registrar: (mensagem) => console.warn(mensagem),
    }),
    () => atualizacao.estado().estado === 'pronta',
  );

  criarJanela();
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (janela) {
      if (janela.isMinimized()) janela.restore();
      janela.focus();
    }
  });
  app.on('web-contents-created', (_evento, conteudo) => {
    travarNavegacao(conteudo);
  });
  void app.whenReady().then(iniciar);
  app.on('window-all-closed', () => {
    app.quit();
  });
}
