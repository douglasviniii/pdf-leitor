# Delvind PDF

Leitor de PDF para Windows feito em Java, com base para uso profissional:

Desenvolvido por DOUGLAS.

- abrir PDF por botÃ£o ou passando o arquivo como argumento;
- navegar por pÃ¡ginas;
- zoom;
- pesquisa de texto;
- grifo por Ã¡rea;
- desenho livre;
- salvar cÃ³pia com alteraÃ§Ãµes;
- assinatura digital A1 em CMS/PDF incremental;
- ponto de extensÃ£o para assinatura A3 via PKCS#11.

## Rodar em desenvolvimento

Requisitos:

- Java 21;
- Maven 3.9+.

Nesta maquina, o JDK e o Maven tambem podem ficar locais em `tools/`, sem ativar no Windows inteiro:

```powershell
.\run.ps1
```

```powershell
mvn javafx:run
```

Para abrir um arquivo direto:

```powershell
mvn javafx:run -Djavafx.args="C:\caminho\arquivo.pdf"
```

## Gerar pacote

```powershell
mvn package
```

Ou usando o JDK/Maven locais:

```powershell
.\build.ps1
```

Para criar o instalador Windows estavel da versao 1.0.4, execute:

```powershell
.\release-windows-stable.ps1
```

Para criar um pacote manualmente, use `jpackage` sobre o JAR gerado. Exemplo base:

```powershell
jpackage --type exe --name "Delvind PDF" --vendor "DOUGLAS" --app-version 1.0.4 --input target --main-jar delvind-pdf-1.0.4.jar --main-class br.com.pdfleitor.app.DelfosPdfLauncher --win-menu --win-shortcut --file-associations packaging/pdf-association.properties
```

## AssociaÃ§Ã£o com PDF

O arquivo `packaging/pdf-association.properties` define a associaÃ§Ã£o com `.pdf` para o instalador.

## ObservaÃ§Ã£o sobre assinatura digital

A assinatura A1 usa arquivo `.pfx` ou `.p12`. Para A3, serÃ¡ necessÃ¡rio escolher o driver PKCS#11 do token/cartÃ£o usado em cada computador.

