import {
  instalarCertificadoFixado,
  PARTICAO_DO_ATUALIZADOR,
  ServicoDeAtualizacao,
} from '@corona/conexao-segura';
import { app, BrowserWindow, net, safeStorage, session, type WebContents } from 'electron';
import electronUpdater from 'electron-updater';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import {
  CANAIS,
  type AvisoDeAtualizacao,
  type AvisoDeSessao,
  type EstadoDaSincronizacao,
} from '../shared/ponte';
import { criarApiDoVendas } from './api';
import { arquivoDeTexto } from './arquivo-de-texto';
import { ServicoDoBanco } from './banco/servico-do-banco';
import { CacheDeFotos } from './fotos/cache-de-fotos';
import { FilaDeFotos } from './fotos/fila-de-fotos';
import { ServicoDeFotos } from './fotos/servico-de-fotos';
import { criarTransporteDeFotos } from './fotos/transporte-de-fotos';
import { RegistroDeContato } from './contato-com-servidor';
import { identificadorDoAparelho } from './dispositivo';
import {
  enderecoDaApi,
  exigirImpressoesFora,
  impressoesDoServidor,
  versaoDoApp,
} from './configuracao';
import { GuardaDeTokens } from './guarda-de-tokens';
import { registrarAtualizacao, registrarPonte, registrarSincronizacao } from './ipc';
import { enderecoDoApp } from './seguranca';
import { ServicoDeSessao } from './servico-de-sessao';
import { ConsultasLocais } from './local/consultas-locais';
import { ApagadorDeDados } from './offline/apagar-dados';
import { ControleDoPrazo } from './offline/prazo-offline';
import { CHAVES, gravarEstado, lerEstado, registrarContato } from './sync/estado-sync';
import { MotorDeSincronizacao } from './sync/motor';
import { ErroDaOperacao, OperacoesLocais } from './sync/operacoes-locais';
import { criarTransporteDoSync } from './sync/transporte-do-sync';
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
  let motor: MotorDeSincronizacao | null = null;
  const guarda = new GuardaDeTokens({
    api,
    cofre,
    arquivo: criarArquivoDoToken(),
    relogio,
    aoContatarOServidor: (credenciais) => contato.registrar(credenciais),
    // Computador desligado pela dona (tarefa 5.8): quarentena das vendas que faltam e depois apagar os dados.
    aoReceberOrdemDeApagar: () => void servico?.ordemDeApagar(),
    aoPerderASessao: (aviso) => {
      motor?.desligar();
      if (aviso.estado === 'app-desatualizado' && aviso.mensagem) {
        servico?.marcarDesatualizado(aviso.mensagem);
      }
      avisarATela(aviso);
    },
  });
  // Banco local cifrado (tarefa 5.2; ADR-0005): só o processo principal o abre, com a chave embrulhada pela senha do
  // vendedor e o resgate pelo cofre do Windows. Por enquanto é preparado a cada entrada com internet.
  const bancoLocal = new ServicoDoBanco({
    pasta: join(app.getPath('userData'), 'dados'),
    cofre,
    // Prazo sem internet (tarefa 5.8): a entrada com internet já é um contato com o servidor.
    aoAbrirComInternet: async (db) => {
      const ultimo = await contato.ler();
      if (!ultimo) return;
      registrarContato(db, ultimo.horaDoServidor, new Date(relogio.agora()));
      if (!lerEstado(db, CHAVES.parametros)) {
        gravarEstado(
          db,
          CHAVES.parametros,
          JSON.stringify({ tempoMaximoOfflineDias: ultimo.tempoMaximoOfflineDias }),
        );
      }
    },
  });
  // O prazo máximo sem internet (10 dias): hora local do último contato, maior hora vista e relógio mexido.
  const prazo = new ControleDoPrazo({
    banco: () => bancoLocal.banco,
    agora: () => new Date(relogio.agora()),
  });
  const transporteDoSync = criarTransporteDoSync({
    baseUrl: enderecoDoServidor,
    versaoDoApp: versaoDoApp(app.getVersion()),
    fetch: (entrada, inicio) => session.defaultSession.fetch(entrada as string | Request, inicio),
  });
  // Apagar os dados do computador desligado (tarefa 5.8; ADR-0013, item 14).
  const pastaDeDados = app.getPath('userData');
  // Fotos no disco (tarefa 5.9): a mesma pasta que o apagar dos dados remove.
  const cacheDeFotos = new CacheDeFotos({ pasta: join(pastaDeDados, 'fotos') });
  const transporteDeFotos = criarTransporteDeFotos({
    baseUrl: enderecoDoServidor,
    versaoDoApp: versaoDoApp(app.getVersion()),
    fetch: (entrada, inicio) => session.defaultSession.fetch(entrada as string | Request, inicio),
  });
  const filaDeFotos = new FilaDeFotos({
    banco: () => bancoLocal.banco,
    transporte: transporteDeFotos,
    comToken: (chamada) => guarda.comToken(chamada),
    cache: cacheDeFotos,
    aoMudar: () => motor?.atualizarEstado(),
    registrar: (mensagem) => console.warn(mensagem),
  });
  const apagador = new ApagadorDeDados({
    lugares: {
      chave: join(pastaDeDados, 'dados', 'chave.json'),
      banco: join(pastaDeDados, 'dados', 'banco.sqlite'),
      pastaDeDados: join(pastaDeDados, 'dados'),
      pastasDeFotos: [join(pastaDeDados, 'fotos')],
      token: join(pastaDeDados, 'sessao', 'renovacao.bin'),
      identificador: join(pastaDeDados, 'aparelho', 'identificador.txt'),
      ultimoContato: join(pastaDeDados, 'aparelho', 'ultimo-contato.json'),
      marca: join(pastaDeDados, 'aparelho', 'apagando.json'),
      pastasDeApoio: [join(pastaDeDados, 'sessao'), join(pastaDeDados, 'aparelho')],
    },
    banco: () => bancoLocal.banco,
    abrirBancoParaApagar: () => bancoLocal.abrirParaApagar(),
    fecharBanco: () => bancoLocal.fechar(),
    enviarParaQuarentena: (corpo) => transporteDoSync.guardarEmQuarentena(corpo),
    credenciais: async () =>
      guarda.comTokenDeRenovacao((token) => Promise.resolve({ token, dispositivoId })),
    relogio,
    registrar: (mensagem) => console.warn(mensagem),
  });
  // Motor de envio e recebimento (tarefa 5.6): no processo principal, com o banco local e a mesma rede (certificado
  // fixado) e a mesma guarda de tokens das telas. Liga depois de entrar com o banco aberto; desliga ao sair, ao perder a
  // sessão e por ordem do servidor. Apagar os dados e o bloqueio são da tarefa 5.8 (aqui o motor só para).
  const motorDoApp = new MotorDeSincronizacao({
    banco: () => bancoLocal.banco,
    transporte: transporteDoSync,
    comToken: (chamada) => guarda.comToken(chamada),
    relogio,
    aoMudarEstado: avisarSincronizacao,
    prazo: () => prazo.situacao(),
    // Depois de cada ciclo com internet, baixa as fotos que faltam; o ciclo não espera (tarefa 5.9).
    depoisDoCiclo: () => void filaDeFotos.rodar(),
    fotos: () => filaDeFotos.progresso(),
    aoReceberOrdem: (ordem) => {
      motor?.desligar();
      if (ordem === 'apagar-dados') void servico?.ordemDeApagar();
    },
    registrar: (mensagem) => console.warn(`Envio: ${mensagem}`),
  });
  motor = motorDoApp;
  // As telas leem e gravam neste banco (tarefa 5.7); cada gravação acorda o motor, que envia 2 s depois.
  const consultas = new ConsultasLocais({
    banco: () => bancoLocal.banco,
    operacoes: new OperacoesLocais({
      banco: () => {
        const db = bancoLocal.banco;
        if (!db) throw new Error('Banco local fechado.');
        return db;
      },
      aoGravar: () => motorDoApp.gravou(),
      // Prazo acabado (ou relógio mexido): nada novo grava; o que já existe continua sendo enviado (tarefa 5.8).
      podeGravar: () => {
        const situacao = prazo.situacao();
        if (situacao.situacao === 'ESGOTADO') {
          throw new ErroDaOperacao(
            situacao.motivo === 'RELOGIO' ? 'RELOGIO_ERRADO' : 'PRAZO_ESGOTADO',
          );
        }
      },
    }),
    agora: () => new Date(relogio.agora()),
    aoMudarOEstado: () => motorDoApp.atualizarEstado(),
  });
  const pararDeVigiar = vigiarAInternet(() => motorDoApp.internetVoltou());
  // A cada minuto: a maior hora do computador já vista sobe e o aviso do prazo se atualiza (tarefa 5.8).
  const batida = setInterval(() => {
    prazo.batida();
    motorDoApp.atualizarEstado();
  }, 60_000);
  app.on('will-quit', () => {
    clearInterval(batida);
    pararDeVigiar();
    motorDoApp.desligar();
    bancoLocal.fechar();
  });
  // A sessão guardada é retomada antes de a tela perguntar "estou logado?".
  servico = new ServicoDeSessao(
    guarda,
    api,
    guarda.restaurar(),
    bancoLocal,
    motorDoApp,
    consultas,
    apagador,
    (estado) => {
      if (estado === 'DESLIGANDO') avisarATela({ estado: 'desligando' });
      else if (estado === 'DESLIGADO') avisarATela({ estado: 'desligado' });
    },
    new ServicoDeFotos({
      cache: cacheDeFotos,
      transporte: transporteDeFotos,
      comToken: (chamada) => guarda.comToken(chamada),
      comInternet: () => motorDoApp.estado().comInternet === true,
    }),
  );
  // O aplicativo fechou no meio de um apagar: continua de onde parou, sem recomeçar a contagem (tarefa 5.8).
  void apagador.emAndamento().then((emAndamento) => {
    if (emAndamento) void servico?.ordemDeApagar();
  });

  registrarPonte(servico, (e) => {
    const quadro = e.senderFrame;
    return (
      quadro !== null &&
      janela !== null &&
      e.sender === janela.webContents &&
      enderecoDoApp(quadro.url, enderecoDeDesenvolvimento)
    );
  });

  registrarSincronizacao(motorDoApp, (e) => {
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
