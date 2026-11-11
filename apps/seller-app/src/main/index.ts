import {
  instalarCertificadoFixado,
  PARTICAO_DO_ATUALIZADOR,
  ServicoDeAtualizacao,
} from '@corona/conexao-segura';
import { app, BrowserWindow, safeStorage, session, type WebContents } from 'electron';
import electronUpdater from 'electron-updater';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { CANAIS, type AvisoDeAtualizacao, type AvisoDeSessao } from '../shared/ponte';
import { criarApiDoVendas } from './api';
import { arquivoDeTexto } from './arquivo-de-texto';
import { RegistroDeContato } from './contato-com-servidor';
import { identificadorDoAparelho } from './dispositivo';
import {
  enderecoDaApi,
  exigirImpressoesFora,
  impressoesDoServidor,
  versaoDoApp,
} from './configuracao';
import { GuardaDeTokens } from './guarda-de-tokens';
import { registrarAtualizacao, registrarPonte } from './ipc';
import { enderecoDoApp } from './seguranca';
import { ServicoDeSessao } from './servico-de-sessao';
import type { ArquivoDoToken, Cofre, Relogio } from './tipos';

const NOME_DO_APP = 'Corona Vendas';
const enderecoDeDesenvolvimento = process.env['ELECTRON_RENDERER_URL'];

app.setName(NOME_DO_APP);

let janela: BrowserWindow | null = null;

const cofre: Cofre = {
  disponivel: () => safeStorage.isAsyncEncryptionAvailable(),
  cifrar: async (texto) => new Uint8Array(await safeStorage.encryptStringAsync(texto)),
  // Ao renovar, o token é gravado de novo, então "shouldReEncrypt" já fica atendido.
  decifrar: async (dados) => (await safeStorage.decryptStringAsync(Buffer.from(dados))).result,
};

function criarArquivoDoToken(): ArquivoDoToken {
  const caminho = join(app.getPath('userData'), 'sessao', 'renovacao.bin');
  return {
    async ler() {
      try {
        return new Uint8Array(await readFile(caminho));
      } catch {
        return null;
      }
    },
    async gravar(dados) {
      await mkdir(dirname(caminho), { recursive: true });
      await writeFile(caminho, dados, { mode: 0o600 });
    },
    async apagar() {
      await rm(caminho, { force: true });
    },
  };
}

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

function avisarAtualizacao(aviso: AvisoDeAtualizacao): void {
  if (janela && !janela.isDestroyed()) janela.webContents.send(CANAIS.atualizacaoMudou, aviso);
}

/** A janela só carrega a tela do próprio aplicativo e não abre nada além dela. */
function travarNavegacao(conteudo: WebContents): void {
  conteudo.setWindowOpenHandler(() => ({ action: 'deny' }));
  conteudo.on('will-navigate', (evento, url) => {
    if (!enderecoDoApp(url, enderecoDeDesenvolvimento)) evento.preventDefault();
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
    backgroundColor: '#FBF7F0',
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
  else void janela.loadFile(join(__dirname, '../renderer/index.html'));
}

async function iniciar(): Promise<void> {
  // Nenhuma permissão do navegador (câmera, microfone, localização...) é concedida.
  session.defaultSession.setPermissionRequestHandler((_conteudo, _permissao, responder) => {
    responder(false);
  });
  session.defaultSession.setPermissionCheckHandler(() => false);

  // O identificador do aparelho nasce na primeira abertura e só o processo principal o conhece.
  const dispositivoId = await identificadorDoAparelho(
    arquivoDeTexto(join(app.getPath('userData'), 'aparelho', 'identificador.txt')),
  );
  const contato = new RegistroDeContato(
    arquivoDeTexto(join(app.getPath('userData'), 'aparelho', 'ultimo-contato.json')),
  );
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
  const api = criarApiDoVendas({
    baseUrl: enderecoDoServidor,
    versaoDoApp: versaoDoApp(app.getVersion()),
    dispositivoId,
    // Rede do Chromium: é ela que consulta o verificador de certificado acima.
    fetch: (entrada, inicio) => session.defaultSession.fetch(entrada as string | Request, inicio),
  });
  // `servico` nasce depois da guarda, e a guarda avisa o serviço pelo `aoPerderASessao`.
  let servico: ServicoDeSessao | null = null;
  const guarda = new GuardaDeTokens({
    api,
    cofre,
    arquivo: criarArquivoDoToken(),
    relogio,
    aoContatarOServidor: (credenciais) => contato.registrar(credenciais),
    aoPerderASessao: (aviso) => {
      if (aviso.estado === 'app-desatualizado' && aviso.mensagem) {
        servico?.marcarDesatualizado(aviso.mensagem);
      }
      avisarATela(aviso);
    },
  });
  // A sessão guardada é retomada antes de a tela perguntar "estou logado?".
  servico = new ServicoDeSessao(guarda, api, guarda.restaurar());

  registrarPonte(servico, (e) => {
    const quadro = e.senderFrame;
    return (
      quadro !== null &&
      janela !== null &&
      e.sender === janela.webContents &&
      enderecoDoApp(quadro.url, enderecoDeDesenvolvimento)
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
      enderecoDoApp(quadro.url, enderecoDeDesenvolvimento)
    );
  });
  if (app.isPackaged) atualizacao.iniciar();

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
