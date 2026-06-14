import assert from 'node:assert/strict';
import { test } from 'node:test';
import { appendMetric } from './rendering.mjs';

test('appendMetric renders dynamic values with textContent', () => {
  const created = [];
  const document = {
    createElement(tagName) {
      const element = {
        tagName,
        children: [],
        className: '',
        textContent: '',
        set innerHTML(value) {
          throw new Error(`innerHTML should not be used: ${value}`);
        },
        appendChild(child) {
          this.children.push(child);
        },
      };
      created.push(element);
      return element;
    },
  };
  const target = {
    children: [],
    appendChild(child) {
      this.children.push(child);
    },
  };

  appendMetric(target, document, 'Heat', '<script>alert(1)</script>');

  assert.equal(target.children.length, 1);
  assert.equal(target.children[0].className, 'metric');
  assert.equal(target.children[0].children[0].textContent, 'Heat');
  assert.equal(target.children[0].children[1].textContent, '<script>alert(1)</script>');
  assert.equal(created.length, 3);
});
