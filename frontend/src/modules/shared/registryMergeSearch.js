/**
 * Registry review (H142) — the merge list as a search.
 *
 * The page renders every ranked entry of the viewer's domains in a <select> (hundreds of events), which nobody can
 * scroll through. This turns it into a search box: the name and the entry's other spellings are matched whatever the
 * diacritics, case or punctuation ("garana" finds «Gărâna Jazz Festival»), every word typed must match, and picking a
 * result sets the select, which stays the submitted field. Without JavaScript the select is still there.
 */

const MAX_RESULTS = 15;
const ALIAS_SEPARATOR = ' | ';

/** Mirrors ArtisticEventRankSupport.normalize: no diacritics, lower case, letters and digits only. */
export function normalizeForSearch(value) {
    return String(value == null ? '' : value)
        .normalize('NFKD')
        .replace(/\p{M}+/gu, '')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, ' ')
        .trim();
}

/**
 * The entries every typed word matches, best first: the name starting with the query, then the words starting the
 * name's words, then anywhere in the name, then through another spelling (returned as {@code alias}).
 */
export function rankMatches(entries, query, max = MAX_RESULTS) {
    const key = normalizeForSearch(query);
    const words = key.split(' ').filter(Boolean);
    if (words.length === 0) return [];
    const holds = text => words.every(w => text.includes(w));
    const startsWords = text => words.every(w => (' ' + text).includes(' ' + w));
    const matches = [];
    entries.forEach(entry => {
        if (holds(entry.nameKey)) {
            const score = entry.nameKey.startsWith(key) ? 0 : (startsWords(entry.nameKey) ? 1 : 2);
            matches.push({ entry, alias: null, score });
            return;
        }
        const i = entry.aliasKeys.findIndex(holds);
        if (i >= 0) matches.push({ entry, alias: entry.aliases[i], score: 3 });
    });
    // by the name's letters, so quotes and marks do not reorder it («Festivalul „Remus…» after «Festivalul Meridian»)
    matches.sort((a, b) => a.score - b.score || a.entry.nameKey.localeCompare(b.entry.nameKey) || a.entry.label.localeCompare(b.entry.label));
    return matches.slice(0, max);
}

function entriesOf(select) {
    return Array.from(select.querySelectorAll('option'))
        .filter(option => option.value)
        .map(option => {
            const name = option.dataset.name || option.textContent.trim();
            const aliases = (option.dataset.aliases || '').split(ALIAS_SEPARATOR).map(a => a.trim()).filter(Boolean);
            const parent = option.parentElement;
            return {
                id: option.value,
                label: option.textContent.trim(),
                name,
                group: parent && parent.tagName === 'OPTGROUP' ? parent.label : '',
                aliases,
                nameKey: normalizeForSearch(name),
                aliasKeys: aliases.map(normalizeForSearch),
            };
        });
}

/**
 * Turns one <select> into the search box (idempotent): its options are the entries, its data-search-* attributes the
 * texts. The activities panel uses it too, for the type a record moves to (H142 slice 7).
 */
export function enhanceSearchableSelect(select) {
    if (!select || select.dataset.searchEnhanced === '1') return;
    select.dataset.searchEnhanced = '1';
    const entries = entriesOf(select);
    const input = document.createElement('input');
    input.type = 'search';
    input.id = `${select.id}-search`;
    input.className = 'form-control form-control-sm';
    input.autocomplete = 'off';
    input.spellcheck = false;
    input.placeholder = select.dataset.searchPlaceholder || '';
    input.setAttribute('role', 'combobox');
    input.setAttribute('aria-autocomplete', 'list');
    input.setAttribute('aria-expanded', 'false');
    const list = document.createElement('ul');
    list.id = `${select.id}-results`;
    list.className = 'app-registry-merge__results';
    list.setAttribute('role', 'listbox');
    list.hidden = true;
    input.setAttribute('aria-controls', list.id);
    const wrap = document.createElement('div');
    wrap.className = 'app-registry-merge';
    wrap.append(input, list);
    select.after(wrap);
    select.hidden = true; // hidden, still submitted
    const label = select.id ? document.querySelector(`label[for="${select.id}"]`) : null;
    if (label) label.htmlFor = input.id;
    if (select.value && select.selectedOptions[0]) input.value = select.selectedOptions[0].textContent.trim();

    let shown = [];
    let active = -1;

    const close = () => {
        list.hidden = true;
        input.setAttribute('aria-expanded', 'false');
        input.removeAttribute('aria-activedescendant');
        active = -1;
    };
    const setActive = index => {
        const items = list.querySelectorAll('[role="option"]');
        items.forEach((li, j) => {
            li.classList.toggle('is-active', j === index);
            li.setAttribute('aria-selected', j === index ? 'true' : 'false');
        });
        active = index;
        if (items[index]) {
            input.setAttribute('aria-activedescendant', items[index].id);
            items[index].scrollIntoView({ block: 'nearest' });
        }
    };
    const pick = entry => {
        select.value = entry.id;
        input.value = entry.label;
        close();
        select.dispatchEvent(new Event('change', { bubbles: true }));
    };
    const render = () => {
        list.replaceChildren();
        active = -1;
        if (normalizeForSearch(input.value).length < 2) {
            shown = [];
            close();
            return;
        }
        shown = rankMatches(entries, input.value);
        if (shown.length === 0) {
            const empty = document.createElement('li');
            empty.className = 'app-registry-merge__empty';
            empty.textContent = select.dataset.searchEmpty || '';
            list.append(empty);
        }
        shown.forEach((match, i) => {
            const li = document.createElement('li');
            li.id = `${list.id}-${i}`;
            li.className = 'app-registry-merge__item';
            li.setAttribute('role', 'option');
            li.setAttribute('aria-selected', 'false');
            const name = document.createElement('span');
            name.textContent = match.entry.label;
            li.append(name);
            const meta = [match.entry.group,
                match.alias ? `${select.dataset.searchAlso || ''}: ${match.alias}` : ''].filter(Boolean).join(' · ');
            if (meta) {
                const small = document.createElement('span');
                small.className = 'app-registry-merge__meta';
                small.textContent = meta;
                li.append(small);
            }
            // mousedown, not click: the input keeps its focus, so its blur does not close the list first
            li.addEventListener('mousedown', event => {
                event.preventDefault();
                pick(match.entry);
            });
            list.append(li);
        });
        list.hidden = false;
        input.setAttribute('aria-expanded', 'true');
    };

    input.addEventListener('input', () => {
        select.value = ''; // typing undoes the pick until another one is made
        render();
    });
    input.addEventListener('search', () => {
        if (!input.value) select.value = '';
    });
    input.addEventListener('keydown', event => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            if (list.hidden) render();
            if (shown.length === 0) return;
            event.preventDefault();
            const last = shown.length - 1;
            setActive(event.key === 'ArrowDown' ? (active >= last ? 0 : active + 1) : (active <= 0 ? last : active - 1));
        } else if (event.key === 'Enter') {
            // the box sits in the ranking form: Enter here never submits it
            event.preventDefault();
            if (active >= 0) pick(shown[active].entry);
            else if (shown.length === 1) pick(shown[0].entry);
        } else if (event.key === 'Escape' && !list.hidden) {
            event.preventDefault();
            close();
        }
    });
    input.addEventListener('blur', close);
}

export function initRegistryMergeSearch() {
    document.querySelectorAll('select[data-registry-merge-search]').forEach(enhanceSearchableSelect);
}
