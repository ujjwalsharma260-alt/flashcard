document.addEventListener("DOMContentLoaded", function () {
  try {
    renderMathInElement(document.body, {
      delimiters: [
        {left: "$$", right: "$$", display: true},
        {left: "\\[", right: "\\]", display: true},
        {left: "\\(", right: "\\)", display: false},
        {left: "$", right: "$", display: false}
      ],
      throwOnError: false
    });
  } catch (e) {}
});
