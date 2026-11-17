import { Botao } from '@corona/ui';
import { definirIdioma, useIdioma, type Idioma } from './idioma';
import { TEXTOS } from './textos';

const OPCOES: readonly { idioma: Idioma; sigla: string }[] = [
  { idioma: 'es', sigla: 'ES' },
  { idioma: 'pt', sigla: 'PT' },
];

/**
 * "ES | PT": dois botões pequenos, na barra do topo (perto do "Sair") e na tela de entrada. O idioma de agora fica
 * destacado; o nome completo aparece ao passar o mouse e é o que o leitor de tela lê. Troca na hora e fica guardado.
 */
export function SeletorDeIdioma() {
  const atual = useIdioma();
  return (
    <div className="vendas-idiomas" role="group" aria-label={TEXTOS.comum.idioma}>
      {OPCOES.map(({ idioma, sigla }) => {
        const nome = TEXTOS.comum.idiomas[idioma];
        return (
          <Botao
            key={idioma}
            variante={idioma === atual ? 'primario' : 'secundario'}
            className="vendas-idiomas__botao"
            lang={idioma}
            title={nome}
            aria-label={nome}
            aria-pressed={idioma === atual}
            onClick={() => definirIdioma(idioma)}
          >
            {sigla}
          </Botao>
        );
      })}
    </div>
  );
}
