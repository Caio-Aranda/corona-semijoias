import { Botao } from '@corona/ui';
import { definirIdioma, useIdioma, type Idioma } from './idioma';

/** Os nomes dos idiomas ficam sempre na própria língua: quem procura o seu idioma o reconhece em qualquer tela. */
const OPCOES: readonly { idioma: Idioma; nome: string; fala: string }[] = [
  { idioma: 'es', nome: 'Español', fala: 'es' },
  { idioma: 'pt', nome: 'Português', fala: 'pt' },
];

/** "Español | Português": a troca fica à vista na entrada e no alto de todas as telas, e vale na hora. */
export function SeletorDeIdioma() {
  const atual = useIdioma();
  return (
    <div className="vendas-idiomas" role="group" aria-label="Idioma / Idioma">
      {OPCOES.map(({ idioma, nome, fala }) => (
        <Botao
          key={idioma}
          variante={idioma === atual ? 'primario' : 'secundario'}
          lang={fala}
          aria-pressed={idioma === atual}
          onClick={() => definirIdioma(idioma)}
        >
          {nome}
        </Botao>
      ))}
    </div>
  );
}
