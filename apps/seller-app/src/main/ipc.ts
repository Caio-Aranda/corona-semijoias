import { ipcMain, type IpcMainInvokeEvent } from 'electron';
import { CANAIS, type EstadoDaSincronizacao } from '../shared/ponte';
import { validarChamada, validarEntrada, validarNovaSenha } from '../shared/validacao';
import { validarPrepararWhatsapp, validarSalvarRelatorio } from '../shared/validacao-do-consignado';
import type { ServicoDeAtualizacao } from '@corona/conexao-segura';
import { guardarIdioma, lerIdiomaGuardado } from './idioma-guardado';
import type { SaidaDosRelatorios } from './saida-dos-relatorios';
import type { ServicoDeSessao } from './servico-de-sessao';

/**
 * Liga os canais da ponte ao serviço. Cada canal confere quem chamou (só a janela do próprio
 * aplicativo) e o formato do que chegou; o que não bate com o formato é recusado, sem detalhes.
 */
export function registrarPonte(
  servico: ServicoDeSessao,
  remetenteConfiavel: (e: IpcMainInvokeEvent) => boolean,
): void {
  const protegido =
    <A extends unknown[], R>(fn: (...args: A) => Promise<R>) =>
    (e: IpcMainInvokeEvent, ...args: A): Promise<R> => {
      if (!remetenteConfiavel(e)) return Promise.reject(new Error('Chamada recusada.'));
      return fn(...args);
    };

  ipcMain.handle(
    CANAIS.entrar,
    protegido(async (dados: unknown) => {
      const v = validarEntrada(dados);
      if (!v) return { tipo: 'erro-inesperado' as const };
      return servico.entrar(v.usuario, v.senha);
    }),
  );
  ipcMain.handle(
    CANAIS.trocarSenha,
    protegido(async (dados: unknown) => {
      const v = validarNovaSenha(dados);
      if (!v) return { tipo: 'erro-inesperado' as const };
      return servico.trocarSenha(v.senhaNova);
    }),
  );
  ipcMain.handle(
    CANAIS.sair,
    protegido(async () => {
      await servico.sair();
    }),
  );
  ipcMain.handle(
    CANAIS.liberarComputador,
    protegido(async () => {
      await servico.liberarComputador();
    }),
  );
  ipcMain.handle(
    CANAIS.estouLogado,
    protegido(() => servico.estouLogado()),
  );
  ipcMain.handle(
    CANAIS.chamarApi,
    protegido(async (pedido: unknown) => {
      const v = validarChamada(pedido);
      if (!v) return { ok: false as const, motivo: 'erro' as const };
      return servico.chamarApi(v);
    }),
  );
}

/** O idioma escolhido fica num arquivo pequeno na pasta de dados do programa (a tela não guarda nada sozinha). */
export function registrarIdioma(
  pastaDeDados: string,
  remetenteConfiavel: (e: IpcMainInvokeEvent) => boolean,
): void {
  ipcMain.handle(CANAIS.idiomaLer, (e: IpcMainInvokeEvent) => {
    if (!remetenteConfiavel(e)) return Promise.reject(new Error('Chamada recusada.'));
    return Promise.resolve(lerIdiomaGuardado(pastaDeDados));
  });
  ipcMain.handle(CANAIS.idiomaGravar, (e: IpcMainInvokeEvent, idioma: unknown) => {
    if (!remetenteConfiavel(e)) return Promise.reject(new Error('Chamada recusada.'));
    guardarIdioma(pastaDeDados, idioma);
    return Promise.resolve();
  });
}

/** Canal que informa a tela sobre a atualização automática (só leitura; a tela não manda nada para o atualizador). */
export function registrarAtualizacao(
  servico: ServicoDeAtualizacao,
  remetenteConfiavel: (e: IpcMainInvokeEvent) => boolean,
): void {
  ipcMain.handle(CANAIS.atualizacaoEstado, (e: IpcMainInvokeEvent) => {
    if (!remetenteConfiavel(e)) return Promise.reject(new Error('Chamada recusada.'));
    return Promise.resolve(servico.estado());
  });
}

/** Canal do estado do envio e do recebimento (só leitura: a tela não manda nada para o motor por aqui). */
export function registrarSincronizacao(
  motor: { estado(): EstadoDaSincronizacao },
  remetenteConfiavel: (e: IpcMainInvokeEvent) => boolean,
): void {
  ipcMain.handle(CANAIS.sincronizacaoEstado, (e: IpcMainInvokeEvent) => {
    if (!remetenteConfiavel(e)) return Promise.reject(new Error('Chamada recusada.'));
    return Promise.resolve(motor.estado());
  });
}

/**
 * Imprimir, salvar em PDF e preparar o WhatsApp do relatório que está na tela (tarefa 9.3). A saída é criada para a janela que
 * pediu (é dela o PDF e a impressão); a tela só manda o nome sugerido, o telefone do cliente e a mensagem curta.
 */
export function registrarRelatorios(
  criarSaida: (e: IpcMainInvokeEvent) => SaidaDosRelatorios,
  remetenteConfiavel: (e: IpcMainInvokeEvent) => boolean,
): void {
  ipcMain.handle(CANAIS.relatorioImprimir, (e: IpcMainInvokeEvent) => {
    if (!remetenteConfiavel(e)) return Promise.reject(new Error('Chamada recusada.'));
    return criarSaida(e).imprimir();
  });
  ipcMain.handle(CANAIS.relatorioSalvarPdf, (e: IpcMainInvokeEvent, dados: unknown) => {
    if (!remetenteConfiavel(e)) return Promise.reject(new Error('Chamada recusada.'));
    const v = validarSalvarRelatorio(dados);
    if (!v) return Promise.resolve({ ok: false as const, motivo: 'erro' as const });
    return criarSaida(e).salvarEmPdf(v.nome);
  });
  ipcMain.handle(CANAIS.relatorioWhatsapp, (e: IpcMainInvokeEvent, dados: unknown) => {
    if (!remetenteConfiavel(e)) return Promise.reject(new Error('Chamada recusada.'));
    const v = validarPrepararWhatsapp(dados);
    if (!v) return Promise.resolve({ ok: false as const, motivo: 'erro' as const });
    return criarSaida(e).prepararWhatsapp(v.telefone, v.mensagem, v.nome, v.semNumero);
  });
}
