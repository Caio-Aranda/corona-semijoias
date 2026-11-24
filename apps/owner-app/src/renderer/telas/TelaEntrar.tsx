import { Aviso, Botao, Campo } from '@corona/ui';
import { useState, type FormEvent } from 'react';
import type { PonteDoCorona, ResultadoDeEntrada } from '../../shared/ponte';
import { SeletorDeIdioma } from '../idioma/SeletorDeIdioma';
import { TEXTOS } from '../textos';
import { MensagemDeEntrada } from './MensagemDeEntrada';
import { TEXTOS_SOLTOS as SOLTOS } from '../textos-soltos';

export interface TelaEntrarProps {
  ponte: PonteDoCorona;
  /** A sessão acabou sozinha: a tela explica antes de pedir a senha de novo. */
  sessaoTerminou?: boolean;
  /** Chamado quando é preciso ver o novo estado (entrou, senha temporária, aplicativo desatualizado). */
  aoMudar: () => void;
}

export function TelaEntrar({ ponte, sessaoTerminou = false, aoMudar }: TelaEntrarProps) {
  const [usuario, setUsuario] = useState('');
  const [senha, setSenha] = useState('');
  const [enviando, setEnviando] = useState(false);
  const [resultado, setResultado] = useState<ResultadoDeEntrada | null>(null);

  async function entrar(evento: FormEvent) {
    evento.preventDefault();
    if (enviando || !usuario.trim() || !senha) return;
    setEnviando(true);
    setResultado(null);
    try {
      const r = await ponte.entrar({ usuario: usuario.trim(), senha });
      if (r.tipo === 'entrou' || r.tipo === 'trocar-senha' || r.tipo === 'app-desatualizado') {
        setSenha('');
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
    <div className="cs-entrada-pagina" style={{ position: 'relative' }}>
      <div className="gestao-idioma--canto">
        <SeletorDeIdioma />
      </div>
      <main>
        <form className="cs-entrada" onSubmit={(e) => void entrar(e)} noValidate>
          <span className="cs-entrada__logo" role="img" aria-label="Corona Semijoias" />
          <p className="cs-entrada__titulo">{SOLTOS.entrada.nomeDoApp}</p>
          <h1 className="cs-entrada__titulo-pagina">{TEXTOS.botoes.entrar}</h1>
          {sessaoTerminou ? (
            <Aviso tipo="atencao" titulo={TEXTOS.sessaoTerminou.titulo}>
              {TEXTOS.sessaoTerminou.texto}
            </Aviso>
          ) : null}
          <MensagemDeEntrada resultado={resultado} />
          <Campo
            rotulo={SOLTOS.entrada.usuario}
            value={usuario}
            onChange={(e) => setUsuario(e.target.value)}
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            autoFocus
          />
          <Campo
            rotulo={SOLTOS.entrada.senha}
            type="password"
            value={senha}
            onChange={(e) => setSenha(e.target.value)}
            autoComplete="current-password"
          />
          <Botao type="submit" variante="primario" grande disabled={enviando}>
            {TEXTOS.botoes.entrar}
          </Botao>
        </form>
      </main>
    </div>
  );
}
