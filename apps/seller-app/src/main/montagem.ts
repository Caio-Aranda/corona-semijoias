import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import type { AvisoDeSessao, EstadoDaSincronizacao } from '../shared/ponte';
import { criarApiDoVendas } from './api';
import { arquivoDeTexto } from './arquivo-de-texto';
import { ServicoDoBanco } from './banco/servico-do-banco';
import { RegistroDeContato } from './contato-com-servidor';
import { identificadorDoAparelho } from './dispositivo';
import { CacheDeFotos } from './fotos/cache-de-fotos';
import { FilaDeFotos } from './fotos/fila-de-fotos';
import { ServicoDeFotos } from './fotos/servico-de-fotos';
import { criarTransporteDeFotos } from './fotos/transporte-de-fotos';
import { GuardaDeTokens } from './guarda-de-tokens';
import { ConsultasLocais } from './local/consultas-locais';
import { ApagadorDeDados } from './offline/apagar-dados';
import { ControleDoPrazo } from './offline/prazo-offline';
import { ServicoDeSessao } from './servico-de-sessao';
import { CHAVES, gravarEstado, lerEstado, registrarContato } from './sync/estado-sync';
import { MotorDeSincronizacao } from './sync/motor';
import { ErroDaOperacao, OperacoesLocais } from './sync/operacoes-locais';
import { criarTransporteDoSync } from './sync/transporte-do-sync';
import type { ArquivoDoToken, Cofre, Relogio } from './tipos';

/**
 * O que a montagem do Corona Vendas recebe de fora. No aplicativo, vem do Electron (`index.ts`): a pasta de dados do
 * usuário, o cofre do Windows, a rede do Chromium com o certificado fixado e as janelas. Nos testes de duas pontas
 * (`testes-sync`, tarefa 5.12), vem do Node: pasta temporária, cofre de teste, `fetch` apontado para o proxy de rede ruim
 * e um relógio de teste. Este arquivo nunca importa `electron`: é a mesma montagem nos dois lugares.
 */
export interface DependenciasDaMontagem {
  /** A pasta de dados do aplicativo (no Electron, `app.getPath('userData')`). */
  pastaDeDados: string;
  enderecoDoServidor: string;
  versaoDoApp: string;
  cofre: Cofre;
  fetch: typeof globalThis.fetch;
  relogio: Relogio;
  avisarATela: (aviso: AvisoDeSessao) => void;
  avisarSincronizacao: (estado: EstadoDaSincronizacao) => void;
  registrar: (mensagem: string) => void;
}

/** O Corona Vendas montado: o serviço que a ponte chama e as peças que o `index.ts` liga ao Electron. */
export interface CoronaVendasMontado {
  servico: ServicoDeSessao;
  motor: MotorDeSincronizacao;
  bancoLocal: ServicoDoBanco;
  apagador: ApagadorDeDados;
  /** A cada minuto: a maior hora do computador já vista sobe e o aviso do prazo se atualiza (tarefa 5.8). */
  batida: () => void;
  /** Ao fechar o aplicativo: para o motor e fecha o banco. */
  fechar: () => void;
}

function criarArquivoDoToken(caminho: string): ArquivoDoToken {
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

/** Monta o processo principal do Corona Vendas (sessão, banco local, motor, fotos, prazo e apagar dos dados). */
export async function montarCoronaVendas(d: DependenciasDaMontagem): Promise<CoronaVendasMontado> {
  const { pastaDeDados, enderecoDoServidor, cofre, relogio } = d;
  // O identificador do aparelho nasce na primeira abertura e só o processo principal o conhece.
  const dispositivoId = await identificadorDoAparelho(
    arquivoDeTexto(join(pastaDeDados, 'aparelho', 'identificador.txt')),
  );
  const contato = new RegistroDeContato(
    arquivoDeTexto(join(pastaDeDados, 'aparelho', 'ultimo-contato.json')),
  );
  const api = criarApiDoVendas({
    baseUrl: enderecoDoServidor,
    versaoDoApp: d.versaoDoApp,
    dispositivoId,
    fetch: d.fetch,
  });
  // `servico` nasce depois da guarda, e a guarda avisa o serviço pelo `aoPerderASessao`.
  let servico: ServicoDeSessao | null = null;
  let motor: MotorDeSincronizacao | null = null;
  const guarda = new GuardaDeTokens({
    api,
    cofre,
    arquivo: criarArquivoDoToken(join(pastaDeDados, 'sessao', 'renovacao.bin')),
    relogio,
    aoContatarOServidor: (credenciais) => contato.registrar(credenciais),
    // Computador desligado pela dona (tarefa 5.8): quarentena das vendas que faltam e depois apagar os dados.
    aoReceberOrdemDeApagar: () => void servico?.ordemDeApagar(),
    aoPerderASessao: (aviso) => {
      motor?.desligar();
      if (aviso.estado === 'app-desatualizado' && aviso.mensagem) {
        servico?.marcarDesatualizado(aviso.mensagem);
      }
      d.avisarATela(aviso);
    },
  });
  // Banco local cifrado (tarefa 5.2; ADR-0005): só o processo principal o abre, com a chave embrulhada pela senha do
  // vendedor e o resgate pelo cofre do Windows. Por enquanto é preparado a cada entrada com internet.
  const bancoLocal = new ServicoDoBanco({
    pasta: join(pastaDeDados, 'dados'),
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
    versaoDoApp: d.versaoDoApp,
    fetch: d.fetch,
  });
  // Fotos no disco (tarefa 5.9): a mesma pasta que o apagar dos dados remove.
  const cacheDeFotos = new CacheDeFotos({ pasta: join(pastaDeDados, 'fotos') });
  const transporteDeFotos = criarTransporteDeFotos({
    baseUrl: enderecoDoServidor,
    versaoDoApp: d.versaoDoApp,
    fetch: d.fetch,
  });
  const filaDeFotos = new FilaDeFotos({
    banco: () => bancoLocal.banco,
    transporte: transporteDeFotos,
    comToken: (chamada) => guarda.comToken(chamada),
    cache: cacheDeFotos,
    aoMudar: () => motor?.atualizarEstado(),
    registrar: d.registrar,
  });
  // Apagar os dados do computador desligado (tarefa 5.8; ADR-0013, item 14).
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
    registrar: d.registrar,
  });
  // Motor de envio e recebimento (tarefa 5.6): no processo principal, com o banco local e a mesma rede (certificado
  // fixado) e a mesma guarda de tokens das telas. Liga depois de entrar com o banco aberto; desliga ao sair, ao perder a
  // sessão e por ordem do servidor. Apagar os dados e o bloqueio são da tarefa 5.8 (aqui o motor só para).
  const motorDoApp = new MotorDeSincronizacao({
    banco: () => bancoLocal.banco,
    transporte: transporteDoSync,
    comToken: (chamada) => guarda.comToken(chamada),
    relogio,
    aoMudarEstado: d.avisarSincronizacao,
    prazo: () => prazo.situacao(),
    // Depois de cada ciclo com internet, baixa as fotos que faltam; o ciclo não espera (tarefa 5.9).
    depoisDoCiclo: () => void filaDeFotos.rodar(),
    fotos: () => filaDeFotos.progresso(),
    aoReceberOrdem: (ordem) => {
      motor?.desligar();
      if (ordem === 'apagar-dados') void servico?.ordemDeApagar();
    },
    registrar: (mensagem) => d.registrar(`Envio: ${mensagem}`),
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
      // A mesma hora do resto da montagem (a hora local de cada gravação conta para o prazo sem internet). No app é o
      // relógio do computador, como antes; nos testes de duas pontas, o relógio do aparelho de teste (tarefa 5.12).
      agora: () => new Date(relogio.agora()),
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
      if (estado === 'DESLIGANDO') d.avisarATela({ estado: 'desligando' });
      else if (estado === 'DESLIGADO') d.avisarATela({ estado: 'desligado' });
    },
    new ServicoDeFotos({
      cache: cacheDeFotos,
      transporte: transporteDeFotos,
      comToken: (chamada) => guarda.comToken(chamada),
      comInternet: () => motorDoApp.estado().comInternet === true,
    }),
  );
  const servicoMontado = servico;
  // O aplicativo fechou no meio de um apagar: continua de onde parou, sem recomeçar a contagem (tarefa 5.8).
  void apagador.emAndamento().then((emAndamento) => {
    if (emAndamento) void servicoMontado.ordemDeApagar();
  });

  return {
    servico: servicoMontado,
    motor: motorDoApp,
    bancoLocal,
    apagador,
    batida: () => {
      prazo.batida();
      motorDoApp.atualizarEstado();
    },
    fechar: () => {
      motorDoApp.desligar();
      bancoLocal.fechar();
    },
  };
}
