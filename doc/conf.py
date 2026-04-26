# Configuration file for the Sphinx documentation builder.
#
# For the full list of built-in configuration values, see the documentation:
# https://www.sphinx-doc.org/en/master/usage/configuration.html

# -- Themes used -------------------------------------------------------------

import os
import sphinx_theme_pd

# -- Project information -----------------------------------------------------
# https://www.sphinx-doc.org/en/master/usage/configuration.html#project-information

version = os.environ.get("KEPPLR_DOC_VERSION", "UNVERSIONED")
release = version
project = "KEPPLR"
copyright = "2025, Hari Nair"
author = "mcfiddish@gmail.com"
highlight_language = "none"
# don't include version in the index page title
html_title = "%s Documentation" % project

# -- General configuration ---------------------------------------------------
# https://www.sphinx-doc.org/en/master/usage/configuration.html#general-configuration

extensions = []

templates_path = ["_templates"]
exclude_patterns = ["_build", "Thumbs.db", ".DS_Store"]


# -- Options for HTML output -------------------------------------------------
# https://www.sphinx-doc.org/en/master/usage/configuration.html#options-for-html-output

# html_theme = 'alabaster'
html_theme = "sphinx_theme_pd"
html_theme_path = [sphinx_theme_pd.get_html_theme_path()]
html_sidebars = {
    "**": ["globaltoc.html", "relations.html", "sourcelink.html", "searchbox.html"]
}

# Add any paths that contain custom static files (such as style sheets) here,
# relative to this directory. They are copied after the builtin static files,
# so a file named "default.css" will overwrite the builtin "default.css".
html_static_path = ["_static"]
# html_style = 'style.css'

rst_prolog = """
.. |arrow| unicode:: U+2192 .. RIGHTWARDS ARROW
"""


def setup(app):
    app.add_css_file("style.css")
