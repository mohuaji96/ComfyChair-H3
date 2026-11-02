import io

P = r'G:\ComfyUI各类文件模型\app\ComfyChairH3\app\src\main\java\sh\hnet\comfychair\ui\screens\H3StudioScreen.kt'
lines = io.open(P, encoding='utf-8').read().split('\n')

start = next(i for i, l in enumerate(lines) if l.strip() == '// 5. 任务')
end = next(i for i, l in enumerate(lines) if l.strip() == '// 6. 高级')

# The card is replaced by the progress bar + status row + queue count that the ordinary
# generation pages already use; nothing here was reachable without the manual 查询 button.
NEW = '''            // 5. 任务：不再有单独的卡片
            // The ordinary four pages show progress in the bar under the title, the result in the
            // preview area and the queue length next to the button. This page now does the same,
            // so a card listing the last three prompt ids has nothing left to say.
'''
lines[start:end] = NEW.split('\n')
io.open(P, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
print('task card removed')
