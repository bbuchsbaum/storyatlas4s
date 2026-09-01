(function () {
  "use strict";

  const exactExpression = /^\s*\{\{\s*([^}]+?)\s*\}\}\s*$/;
  const expression = /\{\{\s*([^}]+?)\s*\}\}/g;
  const directiveSelector = "sc-for, sc-if, template[data-dc-directive]";

  function lookup(source, path) {
    const trimmed = path.trim();
    if (trimmed === "true") return true;
    if (trimmed === "false") return false;
    if (trimmed === "null") return null;
    if (/^-?(?:\d+\.?\d*|\.\d+)$/.test(trimmed)) return Number(trimmed);

    const parts = trimmed.split(".");
    let value = source;
    for (const part of parts) {
      if (value == null || !(part in Object(value))) {
        throw new Error(`Unresolved design-canvas expression: ${trimmed}`);
      }
      value = value[part];
    }
    return value;
  }

  function scopeOf(values, locals) {
    return Object.assign(Object.create(null), values, locals);
  }

  function stringify(value, name) {
    if (typeof value === "function") {
      throw new Error(`Function-valued expression ${name} cannot be interpolated as text`);
    }
    return value == null ? "" : String(value);
  }

  function interpolateString(raw, scope) {
    return raw.replace(expression, (_, name) => stringify(lookup(scope, name), name));
  }

  function interpolateTree(container, scope) {
    function visit(node) {
      if (node.nodeType === Node.TEXT_NODE) {
        if (node.nodeValue.includes("{{")) {
          node.nodeValue = interpolateString(node.nodeValue, scope);
        }
        return;
      }

      if (node.nodeType === Node.ELEMENT_NODE) {
        for (const attribute of Array.from(node.attributes)) {
          if (!attribute.value.includes("{{")) continue;
          const exact = attribute.value.match(exactExpression);
          const value = exact ? lookup(scope, exact[1]) : interpolateString(attribute.value, scope);
          const eventName = attribute.name.toLowerCase();
          if (eventName.startsWith("on") && typeof value === "function") {
            node.removeAttribute(attribute.name);
            node.addEventListener(eventName.slice(2), value);
          } else {
            node.setAttribute(attribute.name, stringify(value, exact ? exact[1] : attribute.name));
          }
        }
      }

      for (const child of Array.from(node.childNodes || [])) visit(child);
    }

    for (const child of Array.from(container.childNodes || [])) visit(child);
  }

  function processDirectives(container, values, locals) {
    let directive = container.querySelector(directiveSelector);
    while (directive) {
      const currentScope = scopeOf(values, locals);
      const replacement = document.createDocumentFragment();
      const directiveKind = directive.dataset.dcDirective || directive.tagName.toLowerCase();
      const children =
        directive.tagName.toLowerCase() === "template" && directive.content
          ? Array.from(directive.content.childNodes)
          : Array.from(directive.childNodes);

      if (directiveKind === "if" || directiveKind === "sc-if") {
        const condition = lookup(
          currentScope,
          directive.getAttribute("value").match(exactExpression)[1]
        );
        if (condition) {
          for (const child of children) {
            replacement.appendChild(child.cloneNode(true));
          }
          processDirectives(replacement, values, locals);
          interpolateTree(replacement, currentScope);
        }
      } else {
        const listExpression = directive.getAttribute("list").match(exactExpression);
        if (!listExpression) throw new Error("sc-for list must be one exact expression");
        const list = lookup(currentScope, listExpression[1]);
        if (!Array.isArray(list)) {
          throw new Error(`sc-for ${listExpression[1]} did not resolve to an array`);
        }
        const localName = directive.getAttribute("as");
        if (!localName) throw new Error("sc-for requires an as attribute");
        for (const item of list) {
          const itemFragment = document.createDocumentFragment();
          for (const child of children) {
            itemFragment.appendChild(child.cloneNode(true));
          }
          const itemLocals = Object.assign(Object.create(null), locals, { [localName]: item });
          processDirectives(itemFragment, values, itemLocals);
          interpolateTree(itemFragment, scopeOf(values, itemLocals));
          replacement.appendChild(itemFragment);
        }
      }

      directive.replaceWith(replacement);
      directive = container.querySelector(directiveSelector);
    }
  }

  class DCLogic {
    constructor(props) {
      this.props = props || {};
      this.state = this.state || {};
      this.__dcRender = null;
    }

    setState(patch) {
      if (patch == null || typeof patch !== "object" || Array.isArray(patch)) {
        throw new Error("DCLogic.setState requires an object patch");
      }
      this.state = Object.assign({}, this.state, patch);
      if (this.__dcRender) this.__dcRender();
    }
  }

  window.DCLogic = DCLogic;

  function renderFailure(error) {
    document.documentElement.dataset.dcResolved = "false";
    document.documentElement.dataset.dcError = String(error && error.message ? error.message : error);
    const pre = document.createElement("pre");
    pre.id = "dc-resolution-error";
    pre.setAttribute("role", "alert");
    pre.style.cssText =
      "white-space:pre-wrap;margin:24px;padding:16px;border:2px solid #852A12;color:#852A12;background:#fff;font:14px/1.5 ui-monospace,monospace";
    pre.textContent = `Design-canvas resolution failed\n${error && error.stack ? error.stack : error}`;
    document.body.replaceChildren(pre);
  }

  function bootstrap() {
    try {
      const root = document.querySelector("x-dc");
      if (!root) throw new Error("Missing x-dc root");
      if (typeof Component !== "function") throw new Error("Missing Component definition");

      for (const style of Array.from(root.querySelectorAll("helmet style"))) {
        const copy = style.cloneNode(true);
        copy.dataset.dcResolvedStyle = "true";
        document.head.appendChild(copy);
      }

      const source = document.createElement("template");
      for (const child of Array.from(root.childNodes)) {
        if (child.nodeType === Node.ELEMENT_NODE && child.tagName.toLowerCase() === "helmet") {
          continue;
        }
        source.content.appendChild(child.cloneNode(true));
      }

      const script = document.querySelector("script[data-dc-script]");
      const props = script && script.dataset.props ? JSON.parse(script.dataset.props) : {};
      const component = new Component(props);

      const render = function () {
        const values = component.renderVals ? component.renderVals() : {};
        if (values == null || typeof values !== "object" || Array.isArray(values)) {
          throw new Error("Component.renderVals must return an object");
        }
        const fragment = source.content.cloneNode(true);
        processDirectives(fragment, values, Object.create(null));
        interpolateTree(fragment, scopeOf(values, Object.create(null)));
        root.replaceChildren(fragment);
        root.style.display = "block";
        document.documentElement.dataset.dcResolved = "true";
        document.documentElement.removeAttribute("data-dc-error");
        window.dispatchEvent(new CustomEvent("dc-resolved"));
      };

      component.__dcRender = render;
      window.__dcComponent = component;
      render();
    } catch (error) {
      renderFailure(error);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bootstrap, { once: true });
  } else {
    queueMicrotask(bootstrap);
  }
})();
