export function appendMetric(target, documentRef, label, value) {
  const item = documentRef.createElement('div');
  item.className = 'metric';

  const labelSpan = documentRef.createElement('span');
  labelSpan.textContent = label;

  const valueStrong = documentRef.createElement('strong');
  valueStrong.textContent = String(value);

  item.appendChild(labelSpan);
  item.appendChild(valueStrong);
  target.appendChild(item);
}
