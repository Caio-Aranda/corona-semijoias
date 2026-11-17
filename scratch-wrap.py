import re, sys
# uso: python scratch-wrap.py arquivo NOME PT_NOME esmodulo nome
# Troca "export const NOME = {" por "export const PT_NOME = {" e acrescenta no fim a versão viva.
base = 'apps/owner-app/src/renderer/'
for arq, nome, esmod, rotulo in [a.split(':') for a in sys.argv[1:]]:
    p = base + arq
    s = open(p, encoding='utf8', newline='').read()
    crlf = '\r\n' in s
    s = s.replace('\r\n', '\n')
    pt = 'PT_' + nome
    assert ('export const %s = {' % nome) in s, arq
    s = s.replace('export const %s = {' % nome, 'export const %s = {' % pt, 1)
    s = s.rstrip('\n') + '\n'
    s = "import { textosVivos } from './idioma/idioma';\nimport { ES_%s } from './idioma/es/%s';\n" % (nome, esmod) + s
    s += "\nexport const %s = textosVivos('%s', %s, ES_%s);\n" % (nome, rotulo, pt, nome)
    if crlf:
        s = s.replace('\n', '\r\n')
    open(p, 'w', encoding='utf8', newline='').write(s)
    print('ok', arq)
