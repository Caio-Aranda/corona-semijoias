import { Botao, CabecalhoDeMarca, Campo } from '@corona/ui';
import { useState, type FormEvent } from 'react';
import type { PonteDoCorona, ResultadoDeEntrada } from '../../shared/ponte';
import { TEXTOS } from '../textos';
import { MensagemDeEntrada } from './MensagemDeEntrada';
import { SeletorDeIdioma } from '../SeletorDeIdioma';

export interface TelaTrocarSenhaProps {
  ponte: PonteDoCorona;
  aoMudar: () => void;
}

/** A tela só ajuda: quem decide se a senha serve é o servidor (que tem as mesmas regras). */
export function senhaPareceBoa(senha: string): boolean {
  return senha.length >= 8 && /\p{L}/u.test(senha) && /\p{N}/u.test(senha);
}

export function TelaTrocarSenha({ ponte, aoMudar }: TelaTrocarSenhaProps) {
  const [senhaNova, setSenhaNova] = useState('');
  const [repeticao, setRepeticao] = useState('');
  const [aviso, setAviso] = useState<{ senhaNova?: string; repeticao?: string }>({});
  const [enviando, setEnviando] = useState(false);
  const [resultado, setResultado] = useState<ResultadoDeEntrada | null>(null);

  async function salvar(evento: FormEvent) {
    evento.preventDefault();
    if (enviando) return;
    setResultado(null);
    const novo: { senhaNova?: string; repeticao?: string } = {};
    if (!senhaPareceBoa(senhaNova)) novo.senhaNova = TEXTOS.senhaNova.naoEstaBoa;
    else if (senhaNova !== repeticao) novo.repeticao = TEXTOS.senhaNova.naoSaoIguais;
    setAviso(novo);
    if (novo.senhaNova || novo.repeticao) return;

    setEnviando(true);
    try {
      const r = await ponte.trocarSenha({ senhaNova });
      if (r.tipo === 'entrou' || r.tipo === 'trocar-senha' || r.tipo === 'app-desatualizado') {
        setSenhaNova('');
        setRepeticao('');
        aoMudar();
        return;
      }
      setResultado(r);
    } catch {
      setResultado({ tipo: 'erro-inesperado' });
    } finally {
      setEnviando(false);
    }
  }

  return (
    <div className="vendas-pagina">
      <CabecalhoDeMarca nomeDoApp="Corona Vendas" acoes={<SeletorDeIdioma />} />
      <main className="vendas-conteudo">
        <form className="vendas-cartao" onSubmit={(e) => void salvar(e)} noValidate>
          <h1 className="vendas-titulo">{TEXTOS.senhaNova.titulo}</h1>
          <p className="vendas-texto">{TEXTOS.senhaNova.texto}</p>
          <div>
            <p className="vendas-texto">{TEXTOS.senhaNova.regrasTitulo}</p>
            <ul className="vendas-regras">
              {TEXTOS.senhaNova.regras.map((regra) => (
                <li key={regra}>{regra}</li>
              ))}
            </ul>
          </div>
          <MensagemDeEntrada resultado={resultado} />
          <Campo
            rotulo={TEXTOS.senhaNova.campoNova}
            type="password"
            value={senhaNova}
            onChange={(e) => setSenhaNova(e.target.value)}
            autoComplete="new-password"
            autoFocus
            {...(aviso.senhaNova ? { erro: aviso.senhaNova } : {})}
          />
          <Campo
            rotulo={TEXTOS.senhaNova.campoRepeticao}
            type="password"
            value={repeticao}
            onChange={(e) => setRepeticao(e.target.value)}
            autoComplete="new-password"
            {...(aviso.repeticao ? { erro: aviso.repeticao } : {})}
          />
          <Botao type="submit" variante="primario" grande disabled={enviando}>
            {TEXTOS.senhaNova.salvar}
          </Botao>
        </form>
      </main>
    </div>
  );
}
