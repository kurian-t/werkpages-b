-- Fill out the high-profile list, which protected exactly one person.
--
-- The table held three rows, all Satya Nadella - one seeded because he had been our own form's
-- placeholder text, two pinned to manager rows. Everybody else walked straight through: somebody
-- added "Steve Jobs, Manager at Puma SE" and nothing flagged it, because Steve Jobs was not on the
-- list at any company at all.
--
-- Two properties of the lookup decide the shape of this seed:
--
--   1. ProofChallengeRepository.isHighProfile compares `full_name` against
--      `fullName.trim().toLowerCase()`. A row stored as "Tim Cook" can therefore never match by
--      name - only lowercase rows work. (The two existing manager-pinned rows carry capitalised
--      names and match by manager_id instead, which is why nobody noticed.)
--
--   2. isNameOnlyMatch fires when a listed name appears at a company that is NOT the one stored.
--      So each row below protects that person everywhere: listing Steve Jobs at Apple is what makes
--      "Steve Jobs at Puma SE" a flagged near miss rather than an ordinary submission.
--
-- Each figure is paired with the company they are best known for leading. Former and late leaders
-- are included deliberately - a name does not stop being impersonation material when somebody
-- leaves or dies, and Steve Jobs is the case that prompted this.
--
-- This is a starting list, not a complete one. Being absent from it is not a judgement; it only
-- means a submission under that name is not automatically asked for proof.

-- The companies these names attach to. ON CONFLICT against the case-insensitive functional index,
-- so an existing company is reused rather than duplicated; slug is filled by companies_auto_slug_trg.
WITH wanted(company_name) AS (
    VALUES ('Apple'), ('Microsoft'), ('Alphabet'), ('Google'), ('Amazon'), ('Meta'), ('Tesla'),
           ('SpaceX'), ('NVIDIA'), ('AMD'), ('Intel'), ('IBM'), ('Oracle'), ('Salesforce'),
           ('Adobe'), ('Cisco'), ('Dell Technologies'), ('HP'), ('Hewlett Packard Enterprise'),
           ('Qualcomm'), ('Broadcom'), ('Netflix'), ('Disney'), ('Uber'), ('Airbnb'), ('Snap'),
           ('Spotify'), ('Shopify'), ('Stripe'), ('Zoom'), ('Dropbox'), ('Slack'), ('LinkedIn'),
           ('TikTok'), ('OpenAI'), ('Anthropic'), ('DeepMind'), ('Block'), ('PayPal'), ('Yahoo'),
           ('eBay'), ('Xerox'), ('General Electric'), ('JPMorgan Chase'), ('Goldman Sachs'),
           ('Bank of America'), ('Citigroup'), ('BlackRock'), ('Berkshire Hathaway'), ('Fidelity'),
           ('Walmart'), ('Target'), ('Starbucks'), ('General Motors'), ('Ford'), ('Delta Air Lines'),
           ('United Airlines'), ('PepsiCo'), ('Coca-Cola'), ('LVMH'), ('Virgin Group'),
           ('The Trump Organization'), ('Twitter'), ('X')
)
INSERT INTO companies (name)
SELECT company_name FROM wanted
ON CONFLICT ((LOWER(TRIM(name)))) DO UPDATE SET updated_at = now();

-- The figures themselves. Names lowercase, because that is what the lookup compares against.
WITH figures(full_name, company_name, note) AS (
    VALUES
        -- Apple
        ('steve jobs',        'Apple',                      'co-founder and former CEO'),
        ('tim cook',          'Apple',                      'CEO'),
        ('jony ive',          'Apple',                      'former chief design officer'),
        -- Microsoft
        ('bill gates',        'Microsoft',                  'co-founder and former CEO'),
        ('steve ballmer',     'Microsoft',                  'former CEO'),
        -- Google / Alphabet
        ('sundar pichai',     'Alphabet',                   'CEO'),
        ('larry page',        'Google',                     'co-founder'),
        ('sergey brin',       'Google',                     'co-founder'),
        ('eric schmidt',      'Google',                     'former CEO'),
        -- Amazon
        ('jeff bezos',        'Amazon',                     'founder and former CEO'),
        ('andy jassy',        'Amazon',                     'CEO'),
        -- Meta
        ('mark zuckerberg',   'Meta',                       'co-founder and CEO'),
        ('sheryl sandberg',   'Meta',                       'former COO'),
        -- Musk and his companies
        ('elon musk',         'Tesla',                      'CEO'),
        -- Semiconductors
        ('jensen huang',      'NVIDIA',                     'co-founder and CEO'),
        ('lisa su',           'AMD',                        'CEO'),
        ('pat gelsinger',     'Intel',                      'former CEO'),
        ('hock tan',          'Broadcom',                   'CEO'),
        ('cristiano amon',    'Qualcomm',                   'CEO'),
        -- Enterprise software and hardware
        ('arvind krishna',    'IBM',                        'CEO'),
        ('ginni rometty',     'IBM',                        'former CEO'),
        ('larry ellison',     'Oracle',                     'co-founder and chairman'),
        ('safra catz',        'Oracle',                     'CEO'),
        ('marc benioff',      'Salesforce',                 'co-founder and CEO'),
        ('shantanu narayen',  'Adobe',                      'CEO'),
        ('chuck robbins',     'Cisco',                      'CEO'),
        ('michael dell',      'Dell Technologies',          'founder and CEO'),
        ('enrique lores',     'HP',                         'CEO'),
        ('carly fiorina',     'HP',                         'former CEO'),
        ('meg whitman',       'Hewlett Packard Enterprise', 'former CEO'),
        ('antonio neri',      'Hewlett Packard Enterprise', 'CEO'),
        ('ursula burns',      'Xerox',                      'former CEO'),
        ('jack welch',        'General Electric',           'former CEO'),
        -- Media and consumer internet
        ('reed hastings',     'Netflix',                    'co-founder and former CEO'),
        ('ted sarandos',      'Netflix',                    'co-CEO'),
        ('bob iger',          'Disney',                     'CEO'),
        ('dara khosrowshahi', 'Uber',                       'CEO'),
        ('travis kalanick',   'Uber',                       'co-founder and former CEO'),
        ('brian chesky',      'Airbnb',                     'co-founder and CEO'),
        ('evan spiegel',      'Snap',                       'co-founder and CEO'),
        ('daniel ek',         'Spotify',                    'co-founder'),
        ('tobi lutke',        'Shopify',                    'co-founder and CEO'),
        ('patrick collison',  'Stripe',                     'co-founder and CEO'),
        ('john collison',     'Stripe',                     'co-founder'),
        ('eric yuan',         'Zoom',                       'founder and CEO'),
        ('drew houston',      'Dropbox',                    'co-founder and CEO'),
        ('stewart butterfield','Slack',                     'co-founder and former CEO'),
        ('ryan roslansky',    'LinkedIn',                   'CEO'),
        ('shou zi chew',      'TikTok',                     'CEO'),
        ('jack dorsey',       'Block',                      'co-founder'),
        ('linda yaccarino',   'X',                          'former CEO'),
        ('marissa mayer',     'Yahoo',                      'former CEO'),
        -- AI labs
        ('sam altman',        'OpenAI',                     'CEO'),
        ('dario amodei',      'Anthropic',                  'co-founder and CEO'),
        ('demis hassabis',    'DeepMind',                   'co-founder and CEO'),
        -- Finance
        ('jamie dimon',       'JPMorgan Chase',             'CEO'),
        ('david solomon',     'Goldman Sachs',              'CEO'),
        ('brian moynihan',    'Bank of America',            'CEO'),
        ('jane fraser',       'Citigroup',                  'CEO'),
        ('larry fink',        'BlackRock',                  'co-founder and CEO'),
        ('warren buffett',    'Berkshire Hathaway',         'chairman and CEO'),
        ('charlie munger',    'Berkshire Hathaway',         'former vice chairman'),
        ('abigail johnson',   'Fidelity',                   'CEO'),
        -- Retail, industry, transport
        ('doug mcmillon',     'Walmart',                    'CEO'),
        ('brian cornell',     'Target',                     'CEO'),
        ('howard schultz',    'Starbucks',                  'former CEO'),
        ('mary barra',        'General Motors',             'CEO'),
        ('jim farley',        'Ford',                       'CEO'),
        ('ed bastian',        'Delta Air Lines',            'CEO'),
        ('scott kirby',       'United Airlines',            'CEO'),
        ('indra nooyi',       'PepsiCo',                    'former CEO'),
        ('ramon laguarta',    'PepsiCo',                    'CEO'),
        ('james quincey',     'Coca-Cola',                  'CEO'),
        ('bernard arnault',   'LVMH',                       'chairman and CEO'),
        ('richard branson',   'Virgin Group',               'founder'),
        ('donald trump',      'The Trump Organization',     'former chairman')
)
INSERT INTO high_profile_figures (full_name, company_id, note)
SELECT f.full_name, c.id, 'seeded: ' || f.note
FROM figures f
JOIN companies c ON LOWER(TRIM(c.name)) = LOWER(TRIM(f.company_name))
ON CONFLICT (full_name, company_id) WHERE full_name IS NOT NULL DO NOTHING;

-- The two manager-pinned rows carry capitalised names, which the name lookup can never match.
-- They still work through manager_id, but normalising them means the name is protected at other
-- companies too - the exact hole that let "Steve Jobs at Puma SE" through.
UPDATE high_profile_figures
SET full_name = LOWER(TRIM(full_name))
WHERE full_name IS NOT NULL AND full_name <> LOWER(TRIM(full_name));
