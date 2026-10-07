var DELIMS = [
  {left: "$$", right: "$$", display: true},
  {left: "\\begin{equation}", right: "\\end{equation}", display: true},
  {left: "\\begin{equation*}", right: "\\end{equation*}", display: true},
  {left: "\\begin{align}", right: "\\end{align}", display: true},
  {left: "\\begin{align*}", right: "\\end{align*}", display: true},
  {left: "\\begin{gather}", right: "\\end{gather}", display: true},
  {left: "\\begin{gather*}", right: "\\end{gather*}", display: true},
  {left: "\\[", right: "\\]", display: true},
  {left: "\\(", right: "\\)", display: false},
  {left: "$", right: "$", display: false}
];
function setContent(html, dark) {
  document.body.style.color = dark ? "#ECECEC" : "#111111";
  var c = document.getElementById("c");
  c.innerHTML = html;
  try { renderMathInElement(c, {delimiters: DELIMS, throwOnError: false}); } catch (e) {}
}
