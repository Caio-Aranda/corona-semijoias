import { Aviso, Botao } from '@corona/ui';
import { useState } from 'react';
import { TEXTOS } from '../textos';

export interface SenhaTemporariaProps {
  nome: string;
  /** Vem da API uma única vez. Fica só na memória desta tela: nunca em disco nem em registro. */
  senha: string;
  /** Data até quando vale, já no formato da tela (dd/mm/aaaa), quando se sabe. */
  validaAte?: string;
  aoFechar: () => void;
}

/**
 * Mostra a senha temporária uma única vez, com o botão de copiar. O pai guarda a senha só em estado
 * de tela e a apaga ao fechar; sair da tela também a descarta, porque o estado some junto.
 */
export function SenhaTemporaria({ nome, senha, validaAte, aoFechar }: SenhaTemporariaProps) {
  const [copia, setCopia] = useState<'nao' | 'sim' | 'falhou'>('nao');
  const t = TEXTOS.usuarios.senha;

  async function copiar() {
    try {
      await navigator.clipboard.writeText(senha);
      setCopia('sim');
    } catch {
      setCopia('falhou');
    }
  }

  return (
    <section className="gestao-senha" aria-labelledby="titulo-senha-temporaria">
      <h2 id="titulo-senha-temporaria" className="gestao-subtitulo">
        {t.titulo(nome)}
      </h2>
      <Aviso tipo="atencao">{t.aviso}</Aviso>
      <p className="gestao-senha__valor" data-testid="senha-temporaria">
        {senha}
      </p>
      <p className="gestao-texto">{t.entregar(nome)}</p>
      {validaAte ? <p className="gestao-texto">{t.validade(validaAte)}</p> : null}
      <div className="gestao-senha__acoes">
        <Botao onClick={() => void copiar()}>{t.copiar}</Botao>
        <Botao variante="primario" onClick={aoFechar}>
          {t.fechar}
        </Botao>
      </div>
      {copia === 'sim' ? <p role="status">{t.copiada}</p> : null}
      {copia === 'falhou' ? <p role="alert">{t.naoCopiou}</p> : null}
    </section>
  );
}
