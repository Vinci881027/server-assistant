import { appendQuoteToDraft } from '../messageActions'

describe('appendQuoteToDraft', () => {
  it('creates a quote block when draft is empty', () => {
    expect(appendQuoteToDraft('', 'line1\nline2')).toBe('> line1\n> line2\n\n')
  })

  it('appends quote block after existing draft', () => {
    expect(appendQuoteToDraft('原本草稿', '引用內容')).toBe('原本草稿\n\n> 引用內容\n\n')
  })

  it('returns normalized draft when message is empty', () => {
    expect(appendQuoteToDraft('  已輸入  ', '   ')).toBe('  已輸入')
  })
})
