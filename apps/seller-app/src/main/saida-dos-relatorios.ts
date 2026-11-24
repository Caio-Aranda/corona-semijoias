import { join } from 'node:path';
import type { ResultadoDeSaidaDoRelatorio } from '../shared/relatorios-do-consignado';
import { ehEnderecoDoWhatsapp, enderecoDoWhatsapp } from '../shared/whatsapp';

/**
 * O que acontece com um relatório pronto na tela (tarefa 9.3; §23.11): imprimir, salvar em PDF e preparar o envio pelo
 * WhatsApp. Funciona sem internet (a abertura do WhatsApp é a única parte que precisa dela). Nada é enviado sozinho: o sistema
 * guarda o PDF numa pasta, mostra a pasta e abre a conversa com a mensagem pronta; quem anexa e envia é o representante.
 * Tudo que toca o Electron entra por `DependenciasDaSaida`, para testar sem janela.
 */
export interface DependenciasDaSaida {
  /** Imprime a tela do relatório (a folha é a única parte visível na impressão). `false` se a pessoa cancelou. */
  imprimirPagina(): Promise<boolean>;
  /** O PDF da tela do relatório, A4, em bytes. */
  paginaEmPdf(): Promise<Uint8Array>;
  /** Pergunta onde guardar; `null` se a pessoa cancelou. */
  escolherOndeSalvar(nomeSugerido: string): Promise<string | null>;
  /** A pasta onde o PDF do WhatsApp fica (sempre a mesma, dentro dos Documentos da pessoa). */
  pastaDosRelatorios(): string;
  gravar(caminho: string, bytes: Uint8Array): Promise<void>;
  mostrarNaPasta(caminho: string): void;
  abrirNoNavegador(endereco: string): Promise<void>;
  comInternet(): boolean;
}

/** Um nome de arquivo seguro: sem barras nem caracteres que o Windows recusa, sempre `.pdf`. */
export function nomeDoArquivo(sugerido: string): string {
  const limpo = sugerido
    .normalize('NFC')
    .replace(/[^\p{L}\p{N} _.-]/gu, '-')
    .replace(/\s+/g, ' ')
    .replace(/\.pdf$/i, '')
    .replace(/^[-. ]+|[-. ]+$/g, '')
    .slice(0, 80);
  return `${limpo || 'relatorio'}.pdf`;
}

export class SaidaDosRelatorios {
  constructor(private readonly d: DependenciasDaSaida) {}

  async imprimir(): Promise<ResultadoDeSaidaDoRelatorio> {
    try {
      return (await this.d.imprimirPagina()) ? { ok: true } : { ok: false, motivo: 'cancelado' };
    } catch {
      return { ok: false, motivo: 'erro' };
    }
  }

  async salvarEmPdf(nomeSugerido: string): Promise<ResultadoDeSaidaDoRelatorio> {
    try {
      const nome = nomeDoArquivo(nomeSugerido);
      const caminho = await this.d.escolherOndeSalvar(nome);
      if (!caminho) return { ok: false, motivo: 'cancelado' };
      await this.d.gravar(caminho, await this.d.paginaEmPdf());
      return { ok: true, arquivo: caminho };
    } catch {
      return { ok: false, motivo: 'erro' };
    }
  }

  /**
   * Guarda o PDF na pasta dos relatórios, mostra a pasta e abre a conversa do cliente com a mensagem pronta. Sem telefone
   * não faz nada; sem internet o PDF fica guardado e a conversa não abre (a tela avisa em linguagem simples).
   */
  async prepararWhatsapp(
    telefone: string | null,
    mensagem: string,
    nomeSugerido: string,
    semNumero = false,
  ): Promise<ResultadoDeSaidaDoRelatorio> {
    const endereco = enderecoDoWhatsapp(telefone, mensagem, semNumero);
    if (!endereco) return { ok: false, motivo: 'sem-telefone' };
    // O processo principal só abre conversas do WhatsApp: confere o endereço montado antes de entregar ao sistema.
    if (!ehEnderecoDoWhatsapp(endereco.split('?')[0] ?? '')) return { ok: false, motivo: 'erro' };
    try {
      const pasta = this.d.pastaDosRelatorios();
      const caminho = join(pasta, nomeDoArquivo(nomeSugerido));
      await this.d.gravar(caminho, await this.d.paginaEmPdf());
      this.d.mostrarNaPasta(caminho);
      if (!this.d.comInternet())
        return { ok: false, motivo: 'sem-internet', arquivo: caminho, pasta };
      await this.d.abrirNoNavegador(endereco);
      return { ok: true, arquivo: caminho, pasta };
    } catch {
      return { ok: false, motivo: 'erro' };
    }
  }
}
