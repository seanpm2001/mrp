<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">

  <xsl:template match="master-results">
    <html>
      <head>
        <title>Tests Results for <xsl:value-of select="@name"/> on <xsl:value-of select="test-run/results/build-parameters/parameter[@key='target.name']/@value"/></title>
        <style>
          body {
          margin-left: 10;
          margin-right: 10;
          font:normal 80% arial,helvetica,sanserif;
          background-color:#FFFFFF;
          color:#000000;
          }
          table.errors { width: 80%; }
          tr.oddrow td { background: #efefef; }
          tr.evenrow td { background: #fff; }
          th, td { text-align: left; vertical-align: top; }
          th { font-weight:bold; background: #ccc; color: black; }
          table, th, td { font-size:100%; border: none }
          h2 { font-weight:bold; font-size:140%; margin-bottom: 5; }
        </style>
      </head>
      <body>
        <xsl:variable name="total-tests" select="count(test-run/results/group/test)"/>
        <xsl:variable name="total-successes" select="count(test-run/results/group/test/result[text()='SUCCESS'])"/>

        <h2>Total Success Rate <xsl:value-of select="$total-successes"/>/<xsl:value-of select="$total-tests"/></h2>
        <table class="errors">
          <tr>
            <th>Result</th>
            <th>Configuration</th>
            <th>Test</th>
            <th>Reason</th>
          </tr>
          <xsl:apply-templates select="test-run/results/group/test/result[text()!='SUCCESS']"/>
        </table>
      </body>
    </html>
  </xsl:template>

  <xsl:template match="test/result">
    <tr>
      <xsl:call-template name="alternated-row"/>
      <td><xsl:value-of select="../result"/></td>
      <td><xsl:value-of select="../../../build-parameters/parameter[@key='config.name']/@value"/></td>
      <td><xsl:value-of select="../selector"/><xsl:value-of select="../class"/></td>
      <td><xsl:value-of select="../result-explanation"/></td>
    </tr>
  </xsl:template>

  <xsl:template name="alternated-row">
    <xsl:attribute name="class">
      <xsl:if test="position() mod 2 = 1">oddrow</xsl:if>
      <xsl:if test="position() mod 2 = 0">evenrow</xsl:if>
    </xsl:attribute>
  </xsl:template>
</xsl:stylesheet>